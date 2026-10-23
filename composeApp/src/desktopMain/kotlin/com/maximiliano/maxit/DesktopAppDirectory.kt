package com.maximiliano.maxit

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

private const val MIGRATION_LOCK = ".storage-migration.lock"
private const val MIGRATION_STAGE = ".storage-migration-v1"
private const val MIGRATION_INIT_PREFIX = ".storage-migration-v1.init-"
private const val MIGRATION_COMPLETE = ".storage-migration-v1.complete"
private const val MIGRATION_OWNER = "Maxit desktop storage migration v1"
private val directoryFsyncAvailable = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal val desktopAppFilesDirectory: File by lazy {
    resolveDesktopAppFilesDirectory(File(System.getProperty("user.home")))
}

/**
 * Resolve before any store opens: copy legacy data, never merge profiles or modify the source.
 * Windows Java cannot fsync directories and may skip read-only file flushes: only best-effort
 * durability is available there, not a guarantee against power loss or system crashes.
 */
@Synchronized
internal fun resolveDesktopAppFilesDirectory(home: File): File {
    if (!directoryFsyncAvailable) {
        System.err.println("Maxit storage: Windows migration durability is best-effort; directory fsync is unavailable and power-loss recovery is not guaranteed.")
    }
    val root = home.toPath().toAbsolutePath().normalize().resolve(".maxit")
    val existed = Files.exists(root, NOFOLLOW_LINKS)
    Files.createDirectories(root)
    check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Maxit storage must be a real directory, not a symbolic link." }
    if (!existed) restrictDirectory(root)
    forceDirectory(root.parent)
    return FileChannel.open(root.resolve(MIGRATION_LOCK), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
        channel.lock().use {
            val complete = root.resolve(MIGRATION_COMPLETE)
            if (Files.exists(complete, NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(complete, NOFOLLOW_LINKS) && Files.size(complete) == 0L) { "Invalid Maxit migration marker." }
                forceFile(complete)
                forceDirectory(root)
                return root.toFile()
            }
            val stage = root.resolve(MIGRATION_STAGE)
            if (Files.exists(stage, NOFOLLOW_LINKS)) {
                validateMigrationStage(stage)
                if (Files.exists(stage.resolve("ready"), NOFOLLOW_LINKS)) {
                    publishMigration(root, stage)
                    return root.toFile()
                }
                check(!hasAppData(root)) { "Existing Maxit data conflicts with an unfinished migration; no data was overwritten." }
                deleteMigrationStage(stage)
            }
            if (hasAppData(root)) {
                completeMigration(root)
                return root.toFile()
            }
            val legacy = root.parent.resolve(".kai")
            val legacyAttributes = try {
                Files.readAttributes(legacy, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                null
            }
            if (legacyAttributes == null) {
                completeMigration(root)
                return root.toFile()
            }
            check(legacyAttributes.isDirectory) { "Legacy storage must be a real directory; it was left unchanged." }
            restrictDirectory(root)
            initializeMigrationStage(root)
            try {
                val data = stage.resolve("data")
                val before = storageSnapshot(legacy)
                val reserved = setOf(MIGRATION_LOCK, MIGRATION_STAGE, MIGRATION_COMPLETE)
                check(
                    before.keys.none {
                        it.toString() in reserved || (it.nameCount == 1 && it.toString().startsWith(MIGRATION_INIT_PREFIX))
                    },
                ) { "Legacy data conflicts with migration metadata; it was left unchanged." }
                for ((relative, entry) in before) {
                    if (relative.toString().isEmpty()) continue
                    val destination = data.resolve(relative)
                    if (entry.directory) {
                        Files.createDirectory(destination)
                    } else {
                        Files.copy(legacy.resolve(relative), destination, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                        forceFile(destination)
                    }
                }
                for ((relative, entry) in before.entries.reversed()) {
                    if (entry.directory) forceDirectory(data.resolve(relative))
                }
                // shortcut: old builds cannot share this lock; close them before migration, use a coordinated backup for live migration.
                check(before == storageSnapshot(legacy)) { "Legacy data changed during migration. Close other app instances and retry; original data was kept." }
                forceFile(Files.createFile(stage.resolve("ready")))
                forceDirectory(stage)
            } catch (error: Exception) {
                try {
                    deleteMigrationStage(stage)
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
            publishMigration(root, stage)
            root.toFile()
        }
    }
}

private fun hasAppData(root: Path): Boolean = Files.newDirectoryStream(root).use { entries ->
    entries.any {
        val name = it.fileName.toString()
        name !in setOf(MIGRATION_LOCK, MIGRATION_STAGE, MIGRATION_COMPLETE) &&
            !(name.startsWith(MIGRATION_INIT_PREFIX) && Files.isDirectory(it, NOFOLLOW_LINKS))
    }
}

private fun forceFile(path: Path) {
    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
}

private fun forceDirectory(path: Path) {
    // shortcut: Java cannot fsync Windows directories; keep the documented best-effort fallback there.
    if (directoryFsyncAvailable) FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
}

private fun completeMigration(root: Path) {
    forceFile(Files.createFile(root.resolve(MIGRATION_COMPLETE)))
    forceDirectory(root)
}

private fun initializeMigrationStage(root: Path) {
    val initial = if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
        Files.createTempDirectory(root, MIGRATION_INIT_PREFIX, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        Files.createTempDirectory(root, MIGRATION_INIT_PREFIX)
    }
    try {
        forceFile(Files.writeString(initial.resolve("owner"), MIGRATION_OWNER, CREATE_NEW, WRITE, NOFOLLOW_LINKS))
        forceDirectory(Files.createDirectory(initial.resolve("data")))
        forceDirectory(initial)
        Files.move(initial, root.resolve(MIGRATION_STAGE), ATOMIC_MOVE)
    } catch (error: Exception) {
        try {
            deleteMigrationStage(initial)
        } catch (cleanup: Exception) {
            error.addSuppressed(cleanup)
        }
        throw error
    }
    forceDirectory(root)
}

private fun restrictDirectory(path: Path) {
    if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
}

private fun validateMigrationStage(stage: Path) {
    check(Files.isDirectory(stage, NOFOLLOW_LINKS)) { "Invalid migration staging directory; no data was removed." }
    val owner = stage.resolve("owner")
    check(Files.isRegularFile(owner, NOFOLLOW_LINKS) && Files.size(owner) == MIGRATION_OWNER.length.toLong() && Files.readString(owner) == MIGRATION_OWNER) {
        "Unrecognized migration staging directory; no data was removed."
    }
    val ready = stage.resolve("ready")
    check(!Files.exists(ready, NOFOLLOW_LINKS) || (Files.isRegularFile(ready, NOFOLLOW_LINKS) && Files.size(ready) == 0L)) { "Invalid migration readiness marker." }
}

private fun publishMigration(root: Path, stage: Path) {
    val data = stage.resolve("data")
    check(Files.isDirectory(data, NOFOLLOW_LINKS)) { "Migration snapshot is missing; original data was kept." }
    Files.newDirectoryStream(data).use { entries ->
        for (entry in entries) {
            val destination = root.resolve(entry.fileName)
            check(!Files.exists(destination, NOFOLLOW_LINKS)) { "Migration destination already exists: ${entry.fileName}; neither copy was overwritten." }
            Files.move(entry, destination)
            forceDirectory(root)
            forceDirectory(data)
        }
    }
    forceDirectory(root)
    forceDirectory(data)
    completeMigration(root)
    deleteMigrationStage(stage)
}

private data class StorageEntry(val directory: Boolean, val size: Long, val modified: FileTime, val key: Any?)

private fun storageSnapshot(root: Path): Map<Path, StorageEntry> {
    val entries = linkedMapOf<Path, StorageEntry>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                entries[root.relativize(dir)] = StorageEntry(true, attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(attrs.isRegularFile) { "Legacy storage contains a link or unsupported file: ${root.relativize(file)}; no external files were followed." }
                entries[root.relativize(file)] = StorageEntry(false, attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
                return FileVisitResult.CONTINUE
            }
        },
    )
    return entries
}

private fun deleteMigrationStage(stage: Path) {
    // Detach before deleting the owner marker so interrupted cleanup cannot strand an invalid canonical stage.
    val discarded = stage.resolveSibling("$MIGRATION_INIT_PREFIX${UUID.randomUUID()}")
    Files.move(stage, discarded, ATOMIC_MOVE)
    forceDirectory(stage.parent)
    Files.walkFileTree(
        discarded,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
    forceDirectory(stage.parent)
}
