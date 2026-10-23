package com.maximiliano.maxit.skills

import com.maximiliano.maxit.getAppFilesDirectory
import com.maximiliano.maxit.getBackgroundDispatcher
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.source
import io.github.vinceglb.filekit.withScopedAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

actual fun createSkillStorage(): SkillStorage = FileSkillStorage(File(getAppFilesDirectory(), "skills"))

internal class FileSkillStorage(private val root: File) : SkillStorage {
    override fun directoryPath(id: String): String {
        validateId(id)
        return File(root, id).absolutePath
    }
    private companion object {
        val lock = Any()
    }

    private fun directory(): File {
        require(!Files.isSymbolicLink(root.toPath())) { "Skill storage must not be a symbolic link." }
        check(root.isDirectory || root.mkdirs()) { "Cannot create skill storage." }
        return root.canonicalFile
    }

    private fun validateId(id: String) {
        require(id.length in 1..64 && Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(id)) { "Invalid skill id." }
    }

    private fun child(base: File, path: String): File {
        validateSkillPath(path)
        val file = File(base, path)
        require(file.canonicalFile == file.absoluteFile && file.canonicalPath.startsWith(base.path + File.separator)) {
            "Skill path escapes storage or uses a symbolic link: $path"
        }
        require(!Files.isSymbolicLink(file.toPath())) { "Skill paths must not be symbolic links." }
        return file
    }

    override suspend fun readAll(): Map<String, List<String>> = withContext(getBackgroundDispatcher()) {
        synchronized(lock) {
            val base = directory()
            val result = linkedMapOf<String, List<String>>()
            for (entry in checkNotNull(base.listFiles()) { "Cannot list skill storage." }) {
                if (entry.name.startsWith('.')) continue
                try {
                    validateId(entry.name)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                try {
                    val folder = child(base, entry.name)
                    if (!folder.isDirectory) continue
                    val files = mutableListOf<String>()
                    var total = 0L
                    fun readDirectory(dir: File) {
                        for (item in checkNotNull(dir.listFiles()) { "Cannot list skill directory." }) {
                            val relative = item.relativeTo(folder).invariantSeparatorsPath
                            val safe = child(folder, relative)
                            if (safe.isDirectory) {
                                readDirectory(safe)
                            } else {
                                require(safe.isFile) { "Unsupported skill file: $relative" }
                                require(files.size < MAX_SKILL_FILES) { "Too many skill files." }
                                val size = safe.length()
                                require(size <= MAX_SKILL_FILE_BYTES) { "Skill file is too large: $relative" }
                                total += size
                                require(total <= MAX_SKILL_BYTES) { "Skill bundle is too large." }
                                files.add(relative)
                            }
                        }
                    }
                    readDirectory(folder)
                    result[entry.name] = files
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Keep damaged bundles visible and removable without hiding healthy skills.
                    result[entry.name] = emptyList()
                }
            }
            result
        }
    }

    override suspend fun readFile(id: String, path: String, maxBytes: Int): ByteArray = withContext(getBackgroundDispatcher()) {
        synchronized(lock) {
            validateId(id)
            require(maxBytes in 1..MAX_SKILL_FILE_BYTES) { "Invalid skill file read limit." }
            val file = child(child(directory(), id), path)
            require(file.isFile) { "No bundled file named '$path' in skill '$id'." }
            file.inputStream().use { readBounded(it, maxBytes) }
        }
    }

    override suspend fun install(id: String, files: Map<String, ByteArray>) = withContext(getBackgroundDispatcher()) {
        synchronized(lock) {
            validateId(id)
            require(files.isNotEmpty() && files.size <= MAX_SKILL_FILES) { "Invalid skill file count." }
            var total = 0L
            files.forEach { (path, bytes) ->
                validateSkillPath(path)
                require(bytes.size <= MAX_SKILL_FILE_BYTES) { "Skill file is too large: $path" }
                total += bytes.size
            }
            require(total <= MAX_SKILL_BYTES) { "Skill bundle is too large." }
            val base = directory()
            val target = child(base, id)
            check(!Files.exists(target.toPath(), NOFOLLOW_LINKS)) { "Skill already exists: $id" }
            val staging = Files.createTempDirectory(base.toPath(), ".install-").toFile()
            try {
                for ((path, bytes) in files) {
                    val file = child(staging, path)
                    check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "Cannot create skill asset directory." }
                    check(!Files.exists(file.toPath(), NOFOLLOW_LINKS)) { "Skill file paths collide: $path" }
                    file.outputStream().use { it.write(bytes) }
                }
                // ATOMIC_MOVE may replace an occupied target; the default move must refuse it.
                Files.move(staging.toPath(), target.toPath())
            } finally {
                deleteTree(staging)
            }
            Unit
        }
    }

    override suspend fun remove(id: String) = withContext(getBackgroundDispatcher()) {
        synchronized(lock) {
            validateId(id)
            deleteTree(File(directory(), id))
        }
    }

    override suspend fun isLegacyMigrated(key: String): Boolean = withContext(getBackgroundDispatcher()) {
        synchronized(lock) {
            validateSkillMigrationKey(key)
            child(directory(), ".legacy-migrated-$key").isFile
        }
    }

    override suspend fun markLegacyMigrated(key: String) = withContext(getBackgroundDispatcher()) {
        synchronized(lock) {
            validateSkillMigrationKey(key)
            val marker = child(directory(), ".legacy-migrated-$key")
            check(marker.isFile || marker.createNewFile()) { "Cannot mark legacy skill migration." }
        }
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        // walkFileTree does not follow links, including links planted inside a bundle.
        Files.walkFileTree(
            file.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

internal actual suspend fun readSkillImport(file: PlatformFile): DownloadedSkill = withContext(getBackgroundDispatcher()) {
    val zip = file.name.endsWith(".zip", ignoreCase = true)
    require(zip || file.name.endsWith(".md", ignoreCase = true)) { "Choose a .md or .zip skill file." }
    val limit = if (zip) MAX_SKILL_BYTES else MAX_SKILL_FILE_BYTES
    val buffer = Buffer()
    file.withScopedAccess {
        file.source().use { source ->
            while (true) {
                coroutineContext.ensureActive()
                val count = source.readAtMostTo(buffer, 8192L)
                if (count == -1L) break
                require(buffer.size <= limit) { "Skill import is too large." }
            }
        }
    }
    val bytes = buffer.readByteArray()
    skillFromFiles(if (zip) readSkillZip(bytes) else mapOf("SKILL.md" to bytes))
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count == -1) return output.toByteArray()
        require(output.size().toLong() + count <= limit) { "Skill data exceeds its size limit." }
        output.write(buffer, 0, count)
    }
}

internal actual fun readSkillZip(bytes: ByteArray): Map<String, ByteArray> {
    require(bytes.size <= MAX_SKILL_BYTES) { "Compressed skill archive is too large." }
    val expectedCount = validateZipDirectory(bytes)
    val files = linkedMapOf<String, ByteArray>()
    val names = mutableSetOf<String>()
    var count = 0
    var total = 0
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++count <= MAX_SKILL_FILES) { "Too many ZIP entries." }
            val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
            validateSkillPath(name)
            require(names.add(name)) { "Duplicate ZIP entry: $name" }
            val content = readBounded(zip, minOf(MAX_SKILL_FILE_BYTES, MAX_SKILL_BYTES - total))
            total += content.size
            if (!entry.isDirectory) files[name] = content
            zip.closeEntry()
        }
    }
    require(count == expectedCount && files.isNotEmpty()) { "Empty or malformed ZIP archive." }
    return files
}

/** ZipInputStream alone accepts missing/truncated central directories as a clean end. */
private fun validateZipDirectory(bytes: ByteArray): Int {
    fun u16(at: Int): Int = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
        u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
    } ?: throw IllegalArgumentException("Malformed ZIP archive: missing directory.")
    val count = u16(end + 10)
    require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == count) { "Split ZIP archives are unsupported." }
    require(count in 1..MAX_SKILL_FILES) { "Empty ZIP archive or too many entries." }
    val offset = u32(end + 16)
    require(offset + u32(end + 12) == end.toLong() && offset <= end) { "Malformed ZIP directory." }
    var cursor = offset.toInt()
    repeat(count) {
        require(cursor + 46 <= end && u32(cursor) == 0x02014b50L) { "Malformed ZIP entry directory." }
        require(u32(cursor + 42) < offset) { "Malformed ZIP entry offset." }
        val host = u16(cursor + 4) ushr 8
        if (host == 3 || host == 19) { // Unix and macOS store the file mode in the upper external attributes.
            require((u16(cursor + 40) and 0xf000) != 0xa000) { "Skill bundles cannot contain symbolic links." }
        }
        cursor += 46 + u16(cursor + 28) + u16(cursor + 30) + u16(cursor + 32)
        require(cursor <= end) { "Truncated ZIP entry directory." }
    }
    require(cursor == end) { "Malformed ZIP directory size." }
    return count
}
