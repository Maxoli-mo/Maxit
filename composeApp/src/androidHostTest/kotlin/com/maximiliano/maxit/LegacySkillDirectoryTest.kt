package com.maximiliano.maxit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacySkillDirectoryTest {
    @Test
    fun `only an explicitly optional missing directory is empty`() {
        val root = Files.createTempDirectory("legacy-skill-listing").toFile()
        try {
            val missing = File(root, "missing")
            assertTrue(listLegacySkillDirectory(missing, "/root/skills", allowMissing = true).isEmpty())
            assertFailsWith<NoSuchFileException> { listLegacySkillDirectory(missing, "/root/skills/bundle/nested", allowMissing = false) }
            val notDirectory = File(root, "file").apply { writeText("content") }
            assertFailsWith<IOException> { listLegacySkillDirectory(notDirectory, "/root/skills", allowMissing = true) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `null listing fails even for optional skills root and succeeds after recovery`() {
        val root = Files.createTempDirectory("legacy-skill-listing").toFile()
        try {
            File(root, "SKILL.md").writeText("manifest")
            val inaccessible = object : File(root.absolutePath) {
                override fun listFiles(): Array<File>? = null
            }
            for (allowMissing in listOf(true, false)) {
                assertFailsWith<IOException> { listLegacySkillDirectory(inaccessible, "/root/skills/bundle", allowMissing) }
            }
            assertEquals(listOf("SKILL.md"), listLegacySkillDirectory(root, "/root/skills/bundle", allowMissing = false).map { it.name })
            assertEquals("manifest", File(root, "SKILL.md").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
