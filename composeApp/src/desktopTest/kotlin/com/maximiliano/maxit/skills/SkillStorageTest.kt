package com.maximiliano.maxit.skills

import com.maximiliano.maxit.testutil.FakeSandboxController
import com.maximiliano.maxit.testutil.FakeSkillStorage
import com.maximiliano.maxit.tools.ShellCommandTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillStorageTest {
    private val temp = Files.createTempDirectory("skill-storage-test-").toFile()
    private val root = File(temp, "skills")
    private val storage = FileSkillStorage(root)
    private val manifest = "---\nname: sample\ndescription: Example\n---\nUse the asset.".encodeToByteArray()
    private val bundle = mapOf("SKILL.md" to manifest, "assets/icon.bin" to byteArrayOf(0, -1, 42))

    @AfterTest
    fun cleanup() {
        Files.walk(temp.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `bundles and migration marker survive reopening and removal`() = runTest {
        assertTrue(storage.readAll().isEmpty())
        assertFalse(storage.isLegacyMigrated())
        storage.install("sample", bundle)
        storage.markLegacyMigrated()
        storage.markLegacyMigrated()
        val reopened = FileSkillStorage(root)
        val files = reopened.readAll().getValue("sample")
        assertEquals(bundle.keys, files.toSet())
        bundle.forEach { (path, bytes) -> assertContentEquals(bytes, reopened.readFile("sample", path, MAX_SKILL_FILE_BYTES)) }
        assertTrue(reopened.isLegacyMigrated())
        reopened.remove("sample")
        reopened.remove("sample")
        assertTrue(reopened.readAll().isEmpty())
        assertTrue(reopened.isLegacyMigrated())
    }

    @Test
    fun `file directory collisions fail without publishing a partial bundle`() = runTest {
        assertFails {
            storage.install(
                "collision",
                linkedMapOf(
                    "scripts" to "Not a directory".encodeToByteArray(),
                    "scripts/run.py" to "print('never executed')".encodeToByteArray(),
                    "SKILL.md" to manifest,
                ),
            )
        }
        assertFalse(File(root, "collision").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".install-") })
        assertTrue(storage.readAll().isEmpty())
    }

    @Test
    fun `source migration markers are independent and survive reopening`() = runTest {
        storage.markLegacyMigrated("source-ubuntu")
        storage.markLegacyMigrated("source-ubuntu")
        val reopened = FileSkillStorage(root)
        assertTrue(reopened.isLegacyMigrated("source-ubuntu"))
        assertFalse(reopened.isLegacyMigrated("source-debian"))
        assertFalse(reopened.isLegacyMigrated())
        reopened.markLegacyMigrated("source-debian")
        assertTrue(storage.isLegacyMigrated("source-debian"))
        assertTrue(storage.isLegacyMigrated("source-ubuntu"))
        assertTrue(storage.readAll().isEmpty())
    }

    @Test
    fun `removal tombstones survive bundle deletion and reopening`() = runTest {
        storage.install("sample", bundle)
        storage.markLegacyMigrated("removed-sample")
        storage.remove("sample")
        val reopened = FileSkillStorage(root)
        assertTrue(reopened.isLegacyMigrated("removed-sample"))
        assertFalse(reopened.isLegacyMigrated("removed-other"))
        assertFalse(reopened.isLegacyMigrated("source-ubuntu"))
        assertTrue(reopened.readAll().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `advertised skill directory resolves binary fonts from an unrelated shell directory`() = runTest {
        val font = byteArrayOf(0, -1, 42)
        storage.install("sample", mapOf("SKILL.md" to manifest, "canvas-fonts/Example.ttf" to font))
        val manager = SkillManager(FakeSandboxController(installed = false), backgroundDispatcher = UnconfinedTestDispatcher(), storage = storage)
        manager.load()
        val selected = checkNotNull(manager.getSkill("sample"))
        val directory = checkNotNull(selected.filesDirectory)
        assertEquals(File(root, "sample").absolutePath, directory)
        assertTrue("Files directory: $directory" in manager.loadSkill("sample"))
        assertTrue("Files directory: $directory" in selected.instructionsWithFiles())
        assertContentEquals(font, File(directory, "canvas-fonts/Example.ttf").readBytes())
        val unrelated = File(temp, "unrelated").apply { mkdirs() }
        assertFalse(File(unrelated, "canvas-fonts/Example.ttf").exists())
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val result = ShellCommandTool.execute(mapOf("command" to "test -f ./canvas-fonts/Example.ttf && printf asset-found", "working_dir" to directory)) as Map<*, *>
            assertEquals(true, result["success"])
            assertEquals("asset-found", result["stdout"])
        }
        assertContentEquals(font, storage.readFile("sample", "canvas-fonts/Example.ttf", MAX_SKILL_FILE_BYTES))
    }

    @Test
    fun `migration marker keys are bounded safe single components`() = runTest {
        for (key in listOf("", "../escape", "a/b", "a\\b", "UPPER", "a_b", "a\u0000b", "a".repeat(129))) {
            assertFails { storage.isLegacyMigrated(key) }
            assertFails { storage.markLegacyMigrated(key) }
        }
        val maximum = "a".repeat(128)
        storage.markLegacyMigrated(maximum)
        assertTrue(storage.isLegacyMigrated(maximum))
    }

    @Test
    fun `fake migration status excludes tombstones and can reset all markers`() = runTest {
        val fake = FakeSkillStorage()
        fake.markLegacyMigrated("removed-sample")
        assertFalse(fake.migrated)
        fake.markLegacyMigrated("source-ubuntu")
        assertTrue(fake.migrated)
        assertFalse(fake.isLegacyMigrated("source-debian"))
        fake.migrated = false
        assertFalse(fake.isLegacyMigrated("source-ubuntu"))
        assertFalse(fake.isLegacyMigrated("removed-sample"))
        fake.migrated = true
        assertTrue(fake.isLegacyMigrated())
    }

    @Test
    fun `duplicate install cannot replace an existing bundle`() = runTest {
        storage.install("sample", bundle)
        assertFails { storage.install("sample", mapOf("SKILL.md" to "replacement".encodeToByteArray())) }
        assertContentEquals(manifest, storage.readFile("sample", "SKILL.md", MAX_SKILL_FILE_BYTES))
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".install-") })
    }

    @Test
    fun `failed staged write leaves no visible or partial bundle`() = runTest {
        assertFails { storage.install("sample", mapOf("asset" to byteArrayOf(1), "asset/child" to byteArrayOf(2))) }
        assertTrue(storage.readAll().isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `hidden staging directories are not returned`() = runTest {
        File(root, ".install-interrupted").mkdirs()
        File(root, ".install-interrupted/SKILL.md").writeBytes(manifest)
        storage.install("sample", bundle)
        assertEquals(setOf("sample"), storage.readAll().keys)
    }

    @Test
    fun `ids and paths cannot escape app storage`() = runTest {
        for (id in listOf("", "../escape", "a/b", "/absolute", ".hidden", "UPPER", "a".repeat(65))) {
            assertFails { storage.install(id, bundle) }
            assertFails { storage.remove(id) }
        }
        for (path in listOf("../escape", "/absolute", "a/../escape", "a\\escape", "C:/escape", "a//b", "a/./b", "a\u0000b")) {
            assertFails { storage.install("sample", mapOf(path to byteArrayOf(1))) }
        }
        assertFalse(File(temp, "escape").exists())
    }

    @Test
    fun `symlinked bundles are isolated and removal only deletes the link`() = runTest {
        storage.install("healthy", bundle)
        val outside = File(temp, "outside").apply { mkdirs() }
        val source = File(outside, "SKILL.md").apply { writeBytes(manifest) }
        val link = File(root, "sample").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        val files = storage.readAll()
        assertEquals(setOf("healthy", "sample"), files.keys)
        assertTrue(files.getValue("sample").isEmpty())
        assertContentEquals(manifest, storage.readFile("healthy", "SKILL.md", MAX_SKILL_FILE_BYTES))
        assertFails { storage.install("sample", bundle) }
        storage.remove("sample")
        assertFalse(Files.isSymbolicLink(link))
        assertContentEquals(manifest, source.readBytes())
        assertEquals(setOf("healthy"), storage.readAll().keys)
    }

    @Test
    fun `nested symlinks are not read and removal never follows them`() = runTest {
        storage.install("healthy", bundle)
        storage.install("sample", bundle)
        val outside = File(temp, "outside.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val runtime = File(root, "sample/.venv/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(runtime, "python").toPath(), outside.toPath())
        val files = storage.readAll()
        assertEquals(setOf("healthy", "sample"), files.keys)
        assertTrue(files.getValue("sample").isEmpty())
        assertContentEquals(manifest, storage.readFile("healthy", "SKILL.md", MAX_SKILL_FILE_BYTES))
        storage.remove("sample")
        assertContentEquals(byteArrayOf(9, 8, 7), outside.readBytes())
        assertEquals(setOf("healthy"), storage.readAll().keys)
    }

    @Test
    fun `dangling bundle links remain visible and removable`() = runTest {
        root.mkdirs()
        val missing = File(temp, "missing")
        val link = File(root, "sample").toPath()
        Files.createSymbolicLink(link, missing.toPath())
        assertTrue(storage.readAll().getValue("sample").isEmpty())
        storage.remove("sample")
        assertFalse(Files.isSymbolicLink(link))
        assertFalse(missing.exists())
    }

    @Test
    fun `foreign invalid-id directories are ignored`() = runTest {
        storage.install("healthy", bundle)
        for (id in listOf("UPPER", "foreign_dir", "a".repeat(65))) {
            val foreign = File(root, id).apply { mkdirs() }
            Files.createSymbolicLink(File(foreign, "asset").toPath(), File(temp, "missing").toPath())
        }
        val files = storage.readAll()
        assertEquals(setOf("healthy"), files.keys)
        assertContentEquals(manifest, storage.readFile("healthy", "SKILL.md", MAX_SKILL_FILE_BYTES))
    }

    @Test
    fun `invalid storage root remains a global error`() = runTest {
        root.writeText("not a directory")
        assertFails { storage.readAll() }
    }

    @Test
    fun `root and migration marker cannot be symlinked`() = runTest {
        val outside = File(temp, "outside").apply { mkdirs() }
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertFails { storage.readAll() }
        Files.delete(root.toPath())
        root.mkdirs()
        val source = File(outside, "marker").apply { writeText("keep") }
        Files.createSymbolicLink(File(root, ".legacy-migrated-complete").toPath(), source.toPath())
        assertFails { storage.isLegacyMigrated() }
        assertFails { storage.markLegacyMigrated() }
        assertEquals("keep", source.readText())
    }

    @Test
    fun `install and reads enforce file bundle and count limits`() = runTest {
        assertFails { storage.install("sample", mapOf("large" to ByteArray(MAX_SKILL_FILE_BYTES + 1))) }
        assertFails { storage.install("sample", (0..MAX_SKILL_FILES).associate { "file-$it" to byteArrayOf() }) }
        assertFails { storage.install("sample", (0..4).associate { "file-$it" to ByteArray(MAX_SKILL_FILE_BYTES) }) }
        storage.install("healthy", bundle)
        storage.install("sample", bundle)
        File(root, "sample/large").outputStream().use { it.write(ByteArray(MAX_SKILL_FILE_BYTES + 1)) }
        val files = storage.readAll()
        assertEquals(setOf("healthy", "sample"), files.keys)
        assertTrue(files.getValue("sample").isEmpty())
        assertContentEquals(manifest, storage.readFile("healthy", "SKILL.md", MAX_SKILL_FILE_BYTES))
        storage.remove("sample")
        assertEquals(setOf("healthy"), storage.readAll().keys)
    }

    @Test
    fun `many large bundles return only file paths`() = runTest {
        repeat(20) { index ->
            val folder = File(root, "skill-$index").apply { mkdirs() }
            File(folder, "SKILL.md").writeBytes(manifest)
            repeat(3) { asset ->
                RandomAccessFile(File(folder, "asset-$asset.bin"), "rw").use { it.setLength(MAX_SKILL_FILE_BYTES.toLong()) }
            }
        }
        val index = storage.readAll()
        assertEquals(20, index.size)
        assertTrue(index.values.all { it.toSet() == setOf("SKILL.md", "asset-0.bin", "asset-1.bin", "asset-2.bin") })
    }

    @Test
    fun `on demand reads enforce limits and recheck paths after listing`() = runTest {
        storage.install("sample", bundle)
        assertEquals(bundle.keys, storage.readAll().getValue("sample").toSet())
        assertFails { storage.readFile("sample", "assets/icon.bin", 2) }
        assertFails { storage.readFile("sample", "SKILL.md", 0) }
        assertFails { storage.readFile("sample", "SKILL.md", MAX_SKILL_FILE_BYTES + 1) }
        assertFails { storage.readFile("sample", "../escape", MAX_SKILL_FILE_BYTES) }
        assertFails { storage.readFile("../escape", "SKILL.md", MAX_SKILL_FILE_BYTES) }
        assertFails { storage.readFile("sample", "missing", MAX_SKILL_FILE_BYTES) }

        val outside = File(temp, "outside.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val asset = File(root, "sample/assets/icon.bin").toPath()
        Files.delete(asset)
        Files.createSymbolicLink(asset, outside.toPath())
        assertFails { storage.readFile("sample", "assets/icon.bin", MAX_SKILL_FILE_BYTES) }
        assertContentEquals(byteArrayOf(9, 8, 7), outside.readBytes())
    }
}
