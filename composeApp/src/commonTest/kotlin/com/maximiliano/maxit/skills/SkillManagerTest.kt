package com.maximiliano.maxit.skills

import com.maximiliano.maxit.linux.LinuxDistro
import com.maximiliano.maxit.testutil.FakeSandboxController
import com.maximiliano.maxit.testutil.FakeSkillStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SkillManagerTest {
    private fun skillMd(name: String, body: String = "Do the thing.") = "---\nname: $name\ndescription: desc\n---\n$body\n"
    private fun manager(storage: FakeSkillStorage, sandbox: FakeSandboxController = FakeSandboxController(installed = false)) = SkillManager(sandbox, backgroundDispatcher = UnconfinedTestDispatcher(), storage = storage)

    @Test
    fun `skills load without an installed sandbox and include nested references`() = runTest {
        val storage = FakeSkillStorage()
        storage.bundles["foo"] = mapOf("SKILL.md" to skillMd("foo").encodeToByteArray(), "core/helper.py" to "print('hi')".encodeToByteArray())
        val mgr = manager(storage)
        mgr.load()
        assertEquals(listOf("core/helper.py"), mgr.getSkill("foo")?.bundledFilePaths)
        assertNotNull(mgr.getSkill("create-skill"))
        assertEquals("print('hi')", mgr.loadSkill("foo", "core/helper.py"))
    }

    @Test
    fun `loaded skill uses the same file inventory and guidance as explicit activation`() = runTest {
        val storage = FakeSkillStorage()
        storage.bundles["canvas-design"] = mapOf(
            "SKILL.md" to skillMd("canvas-design", "Use ./canvas-fonts.").encodeToByteArray(),
            "canvas-fonts/Example.ttf" to byteArrayOf(0, -1, 42),
        )
        val mgr = manager(storage)
        mgr.load()
        val skill = assertNotNull(mgr.getSkill("canvas-design"))
        assertTrue(mgr.loadSkill(skill.id).contains(skill.instructionsWithFiles()))
        assertTrue("- canvas-fonts/Example.ttf" in mgr.loadSkill(skill.id))
        assertTrue("Binary assets such as fonts and images" in mgr.loadSkill(skill.id))
        assertFailsWith<CharacterCodingException> { mgr.loadSkill(skill.id, "canvas-fonts/Example.ttf") }
    }

    @Test
    fun `loaded manifest-only skill does not imply referenced fonts are installed`() = runTest {
        val storage = FakeSkillStorage()
        storage.bundles["canvas-design"] = mapOf("SKILL.md" to skillMd("canvas-design", "Use ./canvas-fonts.").encodeToByteArray())
        val mgr = manager(storage)
        mgr.load()
        val out = mgr.loadSkill("canvas-design")
        assertTrue("instructions-only installation (SKILL.md only)" in out)
        assertTrue("import the complete ZIP or GitHub skill folder" in out)
        assertTrue("preserve any customized SKILL.md, remove the skill through Settings, then import" in out)
        assertTrue("existing skill ids cannot be overwritten" in out)
        assertEquals(setOf("SKILL.md"), storage.bundles.getValue("canvas-design").keys)
    }

    @Test
    fun `unsupported storage exposes no builtins or installed skills`() = runTest {
        val storage = FakeSkillStorage(isSupported = false)
        storage.bundles["foo"] = mapOf("SKILL.md" to skillMd("foo").encodeToByteArray())
        val mgr = manager(storage)
        mgr.load()
        assertTrue(mgr.getInstalled().isEmpty())
        assertNull(mgr.getSkill("create-skill"))
        assertTrue(storage.fileReads.isEmpty())
        assertFailsWith<IllegalStateException> { mgr.createSkill(skillMd("new-skill")) }
        assertEquals(setOf("foo"), storage.bundles.keys)
    }

    @Test
    fun `created instructions survive manager reload and the built-in guide names the creation tool`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        val created = mgr.createSkill(skillMd("new-skill", "New instructions."))
        assertEquals("new-skill", created.id)
        assertEquals(setOf("SKILL.md"), storage.bundles.getValue("new-skill").keys)
        val reloaded = manager(storage)
        reloaded.load()
        assertEquals("New instructions.", reloaded.getSkill("new-skill")?.body?.trim())
        val guide = reloaded.loadSkill("create-skill")
        assertTrue("create_skill" in guide)
        assertTrue("Only after `create_skill` returns success" in guide)
    }

    @Test
    fun `created bundles retain scripts references and binary assets after reload`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        val files = mapOf(
            "scripts/report.py" to "print('report')".encodeToByteArray(),
            "references/format.md" to "Use a short table.".encodeToByteArray(),
            "assets/icon.bin" to byteArrayOf(0, -1, 1),
        )
        val created = mgr.createSkill(skillMd("report"), files)
        assertEquals(files.keys.sorted(), created.bundledFilePaths)
        val reloaded = manager(storage)
        reloaded.load()
        assertEquals("print('report')", reloaded.loadSkill("report", "scripts/report.py"))
        assertContentEquals(files.getValue("assets/icon.bin"), storage.bundles.getValue("report").getValue("assets/icon.bin"))
        assertEquals(setOf("SKILL.md") + files.keys, storage.bundles.getValue("report").keys)
    }

    @Test
    fun `shared installation rejects malformed manifest Unicode without writing files`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        for (malformed in listOf("\uD800", "\uDC00")) {
            assertFailsWith<CharacterCodingException> { mgr.createSkill(skillMd("malformed", malformed)) }
            assertFailsWith<CharacterCodingException> { mgr.install(DownloadedSkill("malformed", "desc", skillMd("malformed", malformed))) }
            assertTrue(storage.bundles.isEmpty())
        }
        val unicode = "Unicode \uD83D\uDE00 remains unchanged."
        mgr.createSkill(skillMd("unicode", unicode))
        assertEquals(unicode, mgr.getSkill("unicode")?.body?.trim())
    }

    @Test
    fun `invalid bundled creation leaves no installed files`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        val invalidFiles = listOf(
            mapOf("SKILL.md" to skillMd("replacement").encodeToByteArray()),
            mapOf("skill.md" to byteArrayOf(1)),
            mapOf("nested/SKILL.md" to skillMd("nested").encodeToByteArray()),
            mapOf("../escape" to byteArrayOf(1)),
            mapOf("/absolute" to byteArrayOf(1)),
            mapOf("scripts\\run.py" to byteArrayOf(1)),
            mapOf("oversized.bin" to ByteArray(MAX_SKILL_FILE_BYTES + 1)),
            (0 until MAX_SKILL_FILES).associate { "$it.txt" to byteArrayOf(1) },
        )
        for (files in invalidFiles) {
            assertFailsWith<IllegalArgumentException> { mgr.createSkill(skillMd("bad"), files) }
            assertTrue(storage.bundles.isEmpty())
            assertNull(mgr.getSkill("bad"))
        }
        assertFailsWith<IllegalArgumentException> { mgr.createSkill(skillMd("bad", "  \n ")) }
        assertTrue(storage.bundles.isEmpty())
    }

    @Test
    fun `reload reads only manifests and references are fetched on demand`() = runTest {
        val storage = FakeSkillStorage()
        val asset = ByteArray(MAX_SKILL_FILE_BYTES)
        repeat(20) { index ->
            val id = "skill-$index"
            storage.bundles[id] = mapOf(
                "SKILL.md" to skillMd(id).encodeToByteArray(),
                "asset-0.bin" to asset,
                "asset-1.bin" to asset,
                "asset-2.bin" to asset,
                "reference.md" to "Original reference".encodeToByteArray(),
            )
        }
        val mgr = manager(storage)
        mgr.load()
        assertEquals(20, mgr.getInstalled().count { !it.isBuiltIn })
        assertTrue(storage.fileReads.all { it.second == "SKILL.md" })
        storage.fileReads.clear()
        storage.bundles["skill-0"] = storage.bundles.getValue("skill-0") + ("reference.md" to "Updated reference".encodeToByteArray())
        assertEquals("Updated reference", mgr.loadSkill("skill-0", "reference.md"))
        assertEquals(listOf("skill-0" to "reference.md"), storage.fileReads)
    }

    @Test
    fun `unreadable manifest does not hide healthy skills or builtins`() = runTest {
        val storage = FakeSkillStorage()
        storage.bundles["broken"] = mapOf("SKILL.md" to byteArrayOf(-1))
        storage.bundles["healthy"] = mapOf("SKILL.md" to skillMd("healthy").encodeToByteArray())
        val mgr = manager(storage)
        mgr.load()
        assertNull(mgr.getSkill("broken"))
        assertNotNull(mgr.getInstalled().first { it.id == "broken" }.loadError)
        assertNotNull(mgr.getSkill("healthy"))
        assertNotNull(mgr.getSkill("create-skill"))
    }

    @Test
    fun `invalid folders are ignored`() = runTest {
        val storage = FakeSkillStorage()
        storage.bundles["wrong"] = mapOf("SKILL.md" to skillMd("other").encodeToByteArray())
        val mgr = manager(storage)
        mgr.load()
        assertNull(mgr.getSkill("wrong"))
        assertNull(mgr.getSkill("other"))
    }

    @Test
    fun `install preserves binary assets and nested paths`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        val bytes = byteArrayOf(0, -1, 127)
        val result = mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("assets/image.png" to bytes)))
        assertEquals("bar", result.id)
        assertTrue(bytes.contentEquals(storage.bundles.getValue("bar").getValue("assets/image.png")))
        assertEquals(listOf("assets/image.png"), result.bundledFilePaths)
    }

    @Test
    fun `duplicate install fails without replacing files`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar", "Original.")))
        assertFailsWith<IllegalArgumentException> { mgr.install(DownloadedSkill("bar", "desc", skillMd("bar", "Replacement."))) }
        mgr.load()
        assertEquals("Original.", mgr.getSkill("bar")?.body?.trim())
    }

    @Test
    fun `unsafe paths and mismatched ids are rejected before storage writes`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        assertFailsWith<IllegalArgumentException> { mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("../escape" to byteArrayOf(1)))) }
        assertFailsWith<IllegalArgumentException> { mgr.install(DownloadedSkill("bar", "desc", skillMd("other"))) }
        assertFailsWith<IllegalArgumentException> { mgr.install(DownloadedSkill("create-skill", "desc", skillMd("create-skill"))) }
        assertTrue(storage.bundles.isEmpty())
    }

    @Test
    fun `uninstall removes app bundle and protects builtins`() = runTest {
        val storage = FakeSkillStorage()
        val mgr = manager(storage)
        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar")))
        mgr.uninstall("bar")
        assertNull(mgr.getSkill("bar"))
        assertTrue(storage.bundles.isEmpty())
        assertFailsWith<IllegalArgumentException> { mgr.uninstall("create-skill") }
    }

    @Test
    fun `legacy migration copies nested files and removal never resurrects them`() = runTest {
        val sandbox = FakeSandboxController(installed = false)
        sandbox.files["/root/skills/legacy/SKILL.md"] = skillMd("legacy")
        sandbox.files["/root/skills/legacy/scripts/read.py"] = "print('legacy')"
        val storage = FakeSkillStorage()
        val mgr = manager(storage, sandbox)
        mgr.migrateLegacy()
        mgr.load()
        assertNotNull(mgr.getSkill("legacy"))
        assertEquals("print('legacy')", mgr.loadSkill("legacy", "scripts/read.py"))
        assertTrue(storage.migrated)
        assertTrue(sandbox.files.isNotEmpty())
        mgr.uninstall("legacy")
        mgr.migrateLegacy()
        mgr.load()
        assertNull(mgr.getSkill("legacy"))
        assertTrue(sandbox.files.isNotEmpty())
    }

    @Test
    fun `loader rejects unknown skills unsafe paths and binary references`() = runTest {
        val mgr = manager(FakeSkillStorage())
        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("binary.bin" to byteArrayOf(-1))))
        assertFailsWith<IllegalStateException> { mgr.loadSkill("missing") }
        assertFailsWith<IllegalArgumentException> { mgr.loadSkill("bar", "../escape") }
        assertFailsWith<CharacterCodingException> { mgr.loadSkill("bar", "binary.bin") }
        assertTrue(mgr.loadSkill("bar").contains("Do the thing."))
    }

    @Test
    fun `binary legacy assets survive and one broken legacy skill does not block healthy ones`() = runTest {
        val sandbox = FakeSandboxController(installed = false)
        sandbox.files["/root/skills/broken/SKILL.md"] = "Invalid frontmatter"
        sandbox.files["/root/skills/legacy/SKILL.md"] = skillMd("legacy")
        val binary = byteArrayOf(0, -1, 127)
        sandbox.binaryFiles["/root/skills/legacy/__pycache__/helper.pyc"] = binary
        val storage = FakeSkillStorage()
        val mgr = manager(storage, sandbox)
        mgr.migrateLegacy()
        mgr.load()
        assertNotNull(mgr.getSkill("legacy"))
        assertTrue(binary.contentEquals(storage.bundles.getValue("legacy").getValue("__pycache__/helper.pyc")))
        assertTrue(!storage.migrated)
        assertEquals("Invalid frontmatter", sandbox.files["/root/skills/broken/SKILL.md"])
        assertTrue(binary.contentEquals(sandbox.binaryFiles.getValue("/root/skills/legacy/__pycache__/helper.pyc")))
        mgr.uninstall("legacy")
        mgr.migrateLegacy()
        mgr.load()
        assertNull(mgr.getSkill("legacy"), "A failed sibling migration must not resurrect removed skills")
    }

    @Test
    fun `legacy completion is scoped to each distribution`() = runTest {
        val sandbox = FakeSandboxController(installed = false)
        sandbox.files["/root/skills/first/SKILL.md"] = skillMd("first")
        val storage = FakeSkillStorage()
        val mgr = manager(storage, sandbox)
        mgr.migrateLegacy()
        val other = LinuxDistro.entries.first { it != sandbox.status.value.distro }
        sandbox.files.clear()
        sandbox.files["/root/skills/second/SKILL.md"] = skillMd("second")
        sandbox.selectDistro(other)
        mgr.migrateLegacy()
        mgr.load()
        assertNotNull(mgr.getSkill("first"))
        assertNotNull(mgr.getSkill("second"))
    }

    @Test
    fun `nested listing failure publishes no partial bundle and recovery keeps healthy sibling`() = runTest {
        val sandbox = FakeSandboxController(installed = false)
        sandbox.files["/root/skills/broken/SKILL.md"] = skillMd("broken")
        sandbox.files["/root/skills/broken/references/nested.md"] = "Keep this reference."
        sandbox.files["/root/skills/healthy/SKILL.md"] = skillMd("healthy", "Healthy original.")
        val originalFiles = sandbox.files.toMap()
        sandbox.listingFailures.add("/root/skills/broken/references")
        val storage = FakeSkillStorage()
        val mgr = manager(storage, sandbox)

        mgr.migrateLegacy()
        assertEquals(setOf("healthy"), storage.bundles.keys)
        assertTrue(!storage.migrated)
        assertEquals(originalFiles, sandbox.files)
        assertTrue(sandbox.listDirectory("/root/skills/broken/references").isNotEmpty(), "Strict migration listing must not change browser behavior")

        sandbox.listingFailures.clear()
        sandbox.files["/root/skills/healthy/SKILL.md"] = skillMd("healthy", "Must not replace installed sibling.")
        mgr.migrateLegacy()
        mgr.load()
        assertTrue(storage.migrated)
        assertEquals("Keep this reference.", mgr.loadSkill("broken", "references/nested.md"))
        assertEquals("Healthy original.", mgr.getSkill("healthy")?.body?.trim())
        assertEquals(originalFiles.keys, sandbox.files.keys)
    }

    @Test
    fun `failed root listing is not a completed empty migration and can retry`() = runTest {
        val sandbox = FakeSandboxController(installed = false)
        sandbox.files["/root/skills/legacy/SKILL.md"] = skillMd("legacy")
        sandbox.listingFailures.add(SkillManager.SKILLS_DIR)
        val storage = FakeSkillStorage()
        val mgr = manager(storage, sandbox)
        assertFailsWith<IllegalStateException> { mgr.migrateLegacy() }
        assertTrue(!storage.migrated)
        assertTrue(storage.bundles.isEmpty())
        sandbox.listingFailures.clear()
        mgr.migrateLegacy()
        assertEquals(setOf("legacy"), storage.bundles.keys)
        assertTrue(storage.migrated)
    }

    @Test
    fun `completed empty destination promotes copied skills only on home completion`() = runTest {
        val sandbox = FakeSandboxController()
        val storage = FakeSkillStorage()
        val mgr = manager(storage, sandbox)
        mgr.migrateLegacy()
        val sourceKey = "source-${sandbox.status.value.distro.name.lowercase()}"
        assertTrue(storage.isLegacyMigrated(sourceKey))
        val listingsBeforeCopy = sandbox.migrationListings.size

        sandbox.updateStatus(sandbox.status.value.copy(working = true, progress = 0.25f))
        sandbox.files["/root/skills/copied/SKILL.md"] = skillMd("copied")
        sandbox.files["/root/skills/copied/references/guide.md"] = "Copied reference."
        sandbox.updateStatus(sandbox.status.value.copy(progress = 0.75f))
        assertTrue(storage.bundles.isEmpty())
        assertEquals(listingsBeforeCopy, sandbox.migrationListings.size)

        sandbox.updateStatus(sandbox.status.value.copy(homeMigrationRevision = 1))
        assertTrue(storage.bundles.isEmpty(), "Wait for the completed copy to settle before importing")
        assertEquals(listingsBeforeCopy, sandbox.migrationListings.size)
        sandbox.updateStatus(sandbox.status.value.copy(working = false, progress = null))
        mgr.load()
        assertEquals("Copied reference.", mgr.loadSkill("copied", "references/guide.md"))
        assertTrue(storage.isLegacyMigrated("$sourceKey-home-1"))
        assertTrue(storage.isLegacyMigrated(sourceKey), "Do not erase earlier migration markers")

        val listingsAfterCopy = sandbox.migrationListings.size
        sandbox.updateStatus(sandbox.status.value.copy(diskUsageMB = 123))
        sandbox.updateStatus(sandbox.status.value.copy(working = true, progress = 0.5f))
        sandbox.updateStatus(sandbox.status.value.copy(working = false, progress = null))
        assertEquals(listingsAfterCopy, sandbox.migrationListings.size)
        assertEquals(setOf("/root/skills/copied/SKILL.md", "/root/skills/copied/references/guide.md"), sandbox.files.keys)
    }

    @Test
    fun `restart after completed home copy promotes new source without overwriting ids or tombstones`() = runTest {
        val storage = FakeSkillStorage()
        val initialSandbox = FakeSandboxController(installed = false)
        val initial = manager(storage, initialSandbox)
        initial.createSkill(skillMd("existing", "App-owned instructions."))
        initial.createSkill(skillMd("removed"))
        initial.uninstall("removed")
        initial.migrateLegacy()

        // A fresh controller reads this revision from the platform's persisted copy completion.
        val restartedSandbox = FakeSandboxController()
        restartedSandbox.files["/root/skills/existing/SKILL.md"] = skillMd("existing", "Legacy replacement.")
        restartedSandbox.files["/root/skills/removed/SKILL.md"] = skillMd("removed")
        restartedSandbox.files["/root/skills/new-copy/SKILL.md"] = skillMd("new-copy")
        restartedSandbox.binaryFiles["/root/skills/new-copy/assets/icon.bin"] = byteArrayOf(0, -1, 42)
        val originalFiles = restartedSandbox.files.toMap()
        restartedSandbox.updateStatus(restartedSandbox.status.value.copy(homeMigrationRevision = 1))
        val restarted = manager(storage, restartedSandbox)
        restarted.migrateLegacy()
        restarted.load()

        assertEquals("App-owned instructions.", restarted.getSkill("existing")?.body?.trim())
        assertNull(restarted.getSkill("removed"))
        assertNotNull(restarted.getSkill("new-copy"))
        assertContentEquals(byteArrayOf(0, -1, 42), storage.bundles.getValue("new-copy").getValue("assets/icon.bin"))
        assertEquals(originalFiles, restartedSandbox.files)
        assertContentEquals(byteArrayOf(0, -1, 42), restartedSandbox.binaryFiles.getValue("/root/skills/new-copy/assets/icon.bin"))
        assertTrue(storage.isLegacyMigrated("removed-removed"))
    }

    @Test
    fun `oversized instructions fail rather than silently truncating`() = runTest {
        val mgr = manager(FakeSkillStorage())
        assertFailsWith<IllegalArgumentException> {
            mgr.install(DownloadedSkill("bar", "desc", skillMd("bar", "x".repeat(MAX_SKILL_TEXT_CHARS))))
        }
    }
}
