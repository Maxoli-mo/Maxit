package com.maximiliano.maxit

import com.maximiliano.maxit.data.EncryptedFileSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppDirectoryTest {
    private lateinit var home: File
    private val legacy get() = File(home, ".kai")
    private val current get() = File(home, ".maxit")
    private val complete get() = File(current, ".storage-migration-v1.complete")
    private val stage get() = File(current, ".storage-migration-v1")

    @BeforeTest
    fun setup() {
        home = Files.createTempDirectory("maxit-app-directory-").toFile()
    }

    @AfterTest
    fun cleanup() {
        Files.walk(home.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun write(base: File, relative: String, bytes: ByteArray): File = File(base, relative).apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    private fun prepareStage(ready: Boolean): File {
        stage.mkdirs()
        write(stage, "owner", "Maxit desktop storage migration v1".encodeToByteArray())
        val data = File(stage, "data").apply { mkdirs() }
        if (ready) File(stage, "ready").createNewFile()
        return data
    }

    @Test
    fun `fresh installs use Maxit without creating a Kai directory`() {
        assertEquals(current, resolveDesktopAppFilesDirectory(home))
        assertTrue(current.isDirectory)
        assertTrue(complete.isFile)
        assertFalse(legacy.exists())
        if ("posix" in current.toPath().fileSystem.supportedFileAttributeViews()) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(current.toPath()))
        }
    }

    @Test
    fun `migration copies binary databases sidecars model markers skills and empty directories verbatim`() {
        val files = linkedMapOf(
            "settings.key" to ByteArray(32) { it.toByte() },
            "settings.aes" to byteArrayOf(0, -1, 12, 40),
            "conversations.db" to byteArrayOf(1, 0, -1, 32),
            "conversations.db-wal" to byteArrayOf(8, 9),
            "conversations.db-shm" to byteArrayOf(10, 11),
            "conversations.db-journal" to byteArrayOf(12, 13),
            "conversations.enc" to byteArrayOf(14, 15),
            "litert_models/custom/model.litertlm" to byteArrayOf(16, 17, -1),
            "litert_models/custom/model.litertlm.sha256" to "user-supplied".encodeToByteArray(),
            "skills/report/SKILL.md" to "Keep historical ~/.kai/path references unchanged.".encodeToByteArray(),
            "skills/report/assets/binary.bin" to byteArrayOf(0, -1, 127),
        )
        files.forEach { (path, bytes) -> write(legacy, path, bytes) }
        File(legacy, "empty").mkdirs()
        val migrated = resolveDesktopAppFilesDirectory(home)
        for ((path, bytes) in files) {
            assertContentEquals(bytes, File(migrated, path).readBytes())
            assertContentEquals(bytes, File(legacy, path).readBytes())
            assertFalse(Files.isSameFile(File(migrated, path).toPath(), File(legacy, path).toPath()))
        }
        assertTrue(File(migrated, "empty").isDirectory)
        File(migrated, "skills/report/SKILL.md").writeText("New Maxit contents")
        assertContentEquals(files.getValue("skills/report/SKILL.md"), File(legacy, "skills/report/SKILL.md").readBytes())
        assertFalse(stage.exists())
    }

    @Test
    fun `an existing empty destination is populated without replacing its directory`() {
        current.mkdirs()
        val before = Files.readAttributes(current.toPath(), BasicFileAttributes::class.java).fileKey()
        write(legacy, "skills/kept/SKILL.md", "Keep me".encodeToByteArray())
        resolveDesktopAppFilesDirectory(home)
        assertEquals(before, Files.readAttributes(current.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals("Keep me", File(current, "skills/kept/SKILL.md").readText())
    }

    @Test
    fun `an existing Maxit profile wins without merging keys or legacy skills`() {
        write(current, "settings.key", byteArrayOf(1))
        write(current, "settings.aes", byteArrayOf(2))
        write(legacy, "settings.key", byteArrayOf(3))
        write(legacy, "settings.aes", byteArrayOf(4))
        write(legacy, "skills/legacy/SKILL.md", "Legacy skill".encodeToByteArray())
        resolveDesktopAppFilesDirectory(home)
        assertContentEquals(byteArrayOf(1), File(current, "settings.key").readBytes())
        assertContentEquals(byteArrayOf(2), File(current, "settings.aes").readBytes())
        assertFalse(File(current, "skills/legacy").exists())
        assertTrue(File(legacy, "skills/legacy/SKILL.md").exists())
        assertTrue(complete.exists())
    }

    @Test
    fun `a completed migration does not resurrect removed data`() {
        write(legacy, "removed.txt", "Old".encodeToByteArray())
        resolveDesktopAppFilesDirectory(home)
        File(current, "removed.txt").delete()
        resolveDesktopAppFilesDirectory(home)
        assertFalse(File(current, "removed.txt").exists())
        assertEquals("Old", File(legacy, "removed.txt").readText())
    }

    @Test
    fun `an interrupted unpublished copy is discarded and recopied from the intact source`() {
        write(legacy, "keep.txt", "Original".encodeToByteArray())
        val data = prepareStage(ready = false)
        write(data, "incomplete.txt", "Partial copy".encodeToByteArray())
        resolveDesktopAppFilesDirectory(home)
        assertEquals("Original", File(current, "keep.txt").readText())
        assertFalse(File(current, "incomplete.txt").exists())
        assertFalse(stage.exists())
    }

    @Test
    fun `an interrupted initializer does not look like an existing user profile`() {
        val orphan = File(current, ".storage-migration-v1.init-crashed")
        write(orphan, "owner", "Partial initializer".encodeToByteArray())
        write(legacy, "keep.txt", "Legacy contents".encodeToByteArray())
        resolveDesktopAppFilesDirectory(home)
        assertEquals("Legacy contents", File(current, "keep.txt").readText())
        assertEquals("Partial initializer", File(orphan, "owner").readText())
        assertTrue(complete.exists())
    }

    @Test
    fun `legacy migration metadata name collisions are never silently dropped`() {
        write(legacy, ".storage-migration-v1.init-user/data.txt", "Keep source data".encodeToByteArray())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals("Keep source data", File(legacy, ".storage-migration-v1.init-user/data.txt").readText())
        assertFalse(complete.exists())
        assertFalse(stage.exists())
    }

    @Test
    fun `a ready partially published snapshot resumes without mixing in later source changes`() {
        write(legacy, "settings.aes", "Later source contents".encodeToByteArray())
        val data = prepareStage(ready = true)
        write(current, "conversations.db", "Published snapshot".encodeToByteArray())
        write(data, "settings.aes", "Same ready snapshot".encodeToByteArray())
        resolveDesktopAppFilesDirectory(home)
        assertEquals("Published snapshot", File(current, "conversations.db").readText())
        assertEquals("Same ready snapshot", File(current, "settings.aes").readText())
        assertEquals("Later source contents", File(legacy, "settings.aes").readText())
        assertTrue(complete.exists())
        assertFalse(stage.exists())
    }

    @Test
    fun `a publication conflict preserves both copies for recovery`() {
        val data = prepareStage(ready = true)
        write(data, "settings.aes", "Staged".encodeToByteArray())
        write(current, "settings.aes", "Existing".encodeToByteArray())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals("Staged", File(data, "settings.aes").readText())
        assertEquals("Existing", File(current, "settings.aes").readText())
        assertTrue(File(stage, "ready").exists())
        assertFalse(complete.exists())
    }

    @Test
    fun `unrecognized staging directories and links are not deleted or followed`() {
        write(stage, "important.txt", "Do not delete".encodeToByteArray())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals("Do not delete", File(stage, "important.txt").readText())
        assertFalse(complete.exists())
    }

    @Test
    fun `legacy links abort safely and a corrected source can be retried`() {
        write(legacy, "safe.txt", "Keep".encodeToByteArray())
        val outside = write(home, "outside.txt", "Untouched".encodeToByteArray())
        val link = File(legacy, "link").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals("Untouched", outside.readText())
        assertEquals("Keep", File(legacy, "safe.txt").readText())
        assertFalse(File(current, "safe.txt").exists())
        assertFalse(stage.exists())
        assertFalse(complete.exists())
        Files.delete(link)
        resolveDesktopAppFilesDirectory(home)
        assertEquals("Keep", File(current, "safe.txt").readText())
    }

    @Test
    fun `storage roots cannot be aliases to another directory`() {
        val outside = File(home, "outside").apply { mkdirs() }
        write(outside, "keep.txt", "Untouched".encodeToByteArray())
        Files.createSymbolicLink(current.toPath(), outside.toPath())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals(setOf("keep.txt"), outside.listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(Files.isSymbolicLink(current.toPath()))
    }

    @Test
    fun `legacy root aliases and migration lock aliases are not followed`() {
        val outside = File(home, "outside").apply { mkdirs() }
        write(outside, "keep.txt", "Untouched".encodeToByteArray())
        Files.createSymbolicLink(legacy.toPath(), outside.toPath())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals(setOf("keep.txt"), outside.listFiles().orEmpty().map { it.name }.toSet())
        assertFalse(complete.exists())
        Files.delete(legacy.toPath())
        val lock = File(current, ".storage-migration.lock").toPath()
        Files.deleteIfExists(lock)
        Files.createSymbolicLink(lock, File(outside, "keep.txt").toPath())
        assertFails { resolveDesktopAppFilesDirectory(home) }
        assertEquals("Untouched", File(outside, "keep.txt").readText())
    }

    @Test
    fun `read-only model files keep their contents and permissions`() {
        if ("posix" !in home.toPath().fileSystem.supportedFileAttributeViews()) return
        val model = write(legacy, "litert_models/model/file.litertlm", byteArrayOf(1, 2, 3))
        val permissions = PosixFilePermissions.fromString("r--------")
        Files.setPosixFilePermissions(model.toPath(), permissions)
        resolveDesktopAppFilesDirectory(home)
        val copied = File(current, "litert_models/model/file.litertlm")
        assertContentEquals(model.readBytes(), copied.readBytes())
        assertEquals(permissions, Files.getPosixFilePermissions(copied.toPath()))
        assertEquals(permissions, Files.getPosixFilePermissions(model.toPath()))
    }

    @Test
    fun `unreadable legacy storage is not treated as an absent profile`() {
        if ("posix" !in home.toPath().fileSystem.supportedFileAttributeViews()) return
        write(legacy, "keep.txt", "Original".encodeToByteArray())
        val original = Files.getPosixFilePermissions(legacy.toPath())
        Files.setPosixFilePermissions(legacy.toPath(), emptySet())
        try {
            if (Files.isReadable(legacy.toPath())) return
            assertFails { resolveDesktopAppFilesDirectory(home) }
            assertFalse(complete.exists())
            assertFalse(stage.exists())
            assertEquals(emptySet(), Files.getPosixFilePermissions(legacy.toPath()))
        } finally {
            Files.setPosixFilePermissions(legacy.toPath(), original)
        }
        assertEquals("Original", File(legacy, "keep.txt").readText())
    }

    @Test
    fun `parallel initializers resolve a single complete profile`() {
        write(legacy, "data.bin", ByteArray(1024) { it.toByte() })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { resolveDesktopAppFilesDirectory(home) } }).map { it.get() }
            assertEquals(listOf(current, current), results)
            assertContentEquals(File(legacy, "data.bin").readBytes(), File(current, "data.bin").readBytes())
            assertTrue(complete.exists())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `copied encrypted settings decrypt with the copied key and future writes leave the source intact`() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { (it + 10).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = iv + cipher.doFinal("""{"example":"preserved"}""".encodeToByteArray())
        write(legacy, "settings.key", key)
        write(legacy, "settings.aes", encrypted)
        if ("posix" in legacy.toPath().fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(File(legacy, "settings.key").toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        val directory = resolveDesktopAppFilesDirectory(home)
        val settings = EncryptedFileSettings(directory)
        assertEquals("preserved", settings.getString("example", ""))
        settings.putString("new-value", "New profile write")
        assertEquals("New profile write", EncryptedFileSettings(directory).getString("new-value", ""))
        assertContentEquals(encrypted, File(legacy, "settings.aes").readBytes())
        assertContentEquals(key, File(legacy, "settings.key").readBytes())
        assertContentEquals(key, File(directory, "settings.key").readBytes())
        assertTrue(Files.isRegularFile(File(directory, "settings.key").toPath(), NOFOLLOW_LINKS))
    }
}
