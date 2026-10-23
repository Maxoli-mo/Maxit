package com.maximiliano.maxit.skills

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SkillImportTest {
    private val markdown = "---\nname: sample\ndescription: Example\n---\nUse assets/icon.bin."
    private val manifest = markdown.encodeToByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun withZipMode(bytes: ByteArray, host: Int, mode: Int): ByteArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = buffer.getInt(bytes.size - 6)
        repeat(buffer.getShort(bytes.size - 12).toInt() and 0xffff) {
            assertEquals(0x02014b50, buffer.getInt(cursor))
            buffer.put(cursor + 5, host.toByte())
            buffer.putShort(cursor + 40, mode.toShort())
            cursor += 46 + (buffer.getShort(cursor + 28).toInt() and 0xffff) +
                (buffer.getShort(cursor + 30).toInt() and 0xffff) +
                (buffer.getShort(cursor + 32).toInt() and 0xffff)
        }
        return bytes
    }

    @Test
    fun `raw manifest size is checked before frontmatter parsing using the character limit`() {
        val maximum = markdown.padEnd(MAX_SKILL_TEXT_CHARS, 'é')
        assertEquals(maximum, skillFromFiles(mapOf("SKILL.md" to maximum.encodeToByteArray())).rawSkillMd)
        for (oversized in listOf(maximum + "é", " ".repeat(MAX_SKILL_TEXT_CHARS + 1))) {
            val error = assertFails { skillFromFiles(mapOf("SKILL.md" to oversized.encodeToByteArray())) }
            assertEquals("SKILL.md must be at most $MAX_SKILL_TEXT_CHARS characters.", error.message)
        }
    }

    @Test
    fun `ZIP symlinks are rejected from Unix central directory modes`() {
        for (host in listOf(3, 19)) {
            for (name in listOf("SKILL.md", "assets/link", "outside/link/")) {
                val bytes = withZipMode(zip(name to "../target".encodeToByteArray()), host, 0xa1ff)
                val error = assertFails { readSkillZip(bytes) }
                assertEquals("Skill bundles cannot contain symbolic links.", error.message)
            }
        }
    }

    @Test
    fun `normal Unix and Windows ZIP modes retain binary and font assets`() {
        val binary = byteArrayOf(0, -1, 42, -128)
        val font = byteArrayOf(0, 1, 0, 0, -1)
        for ((host, mode) in listOf(3 to 0x81a4, 3 to 0x81ed, 19 to 0x81a4, 0 to 0, 10 to 0, 0 to 0xa1ff)) {
            val archive = withZipMode(
                zip("SKILL.md" to manifest, "assets/icon.bin" to binary, "canvas-fonts/Example.ttf" to font),
                host,
                mode,
            )
            val skill = skillFromFiles(readSkillZip(archive))
            assertEquals(setOf("assets/icon.bin", "canvas-fonts/Example.ttf"), skill.files.keys)
            assertContentEquals(binary, skill.files.getValue("assets/icon.bin"))
            assertContentEquals(font, skill.files.getValue("canvas-fonts/Example.ttf"))
        }
    }

    @Test
    fun `wrapped archives preserve binary assets and normalize only the skill prefix`() {
        val binary = byteArrayOf(0, -1, 1, -128)
        val files = readSkillZip(
            zip(
                "project/" to byteArrayOf(),
                "project/sample/SKILL.md" to manifest,
                "project/sample/assets/icon.bin" to binary,
                "project/README.md" to "Outside skill".encodeToByteArray(),
            ),
        )
        val skill = skillFromFiles(files)
        assertEquals("sample", skill.id)
        assertEquals(markdown, skill.rawSkillMd)
        assertEquals(setOf("assets/icon.bin"), skill.files.keys)
        assertContentEquals(binary, skill.files.getValue("assets/icon.bin"))
    }

    @Test
    fun `local markdown and ZIP imports read actual contents without changing sources`() = runTest {
        val temp = Files.createTempDirectory("skill-import-test-").toFile()
        try {
            val md = temp.resolve("custom.MD").apply { writeBytes(manifest) }
            assertEquals("sample", readSkillImport(PlatformFile(md)).id)
            assertContentEquals(manifest, md.readBytes())
            val bytes = zip("SKILL.md" to manifest, "asset.bin" to byteArrayOf(5, -1))
            val archive = temp.resolve("custom.ZIP").apply { writeBytes(bytes) }
            assertContentEquals(byteArrayOf(5, -1), readSkillImport(PlatformFile(archive)).files.getValue("asset.bin"))
            assertContentEquals(bytes, archive.readBytes())
            val unsupported = temp.resolve("custom.txt").apply { writeBytes(manifest) }
            assertFails { readSkillImport(PlatformFile(unsupported)) }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `local streaming imports enforce markdown and compressed limits`() = runTest {
        val temp = Files.createTempDirectory("skill-import-limit-test-").toFile()
        try {
            for ((extension, limit) in listOf("md" to MAX_SKILL_FILE_BYTES, "zip" to MAX_SKILL_BYTES)) {
                val file = temp.resolve("large.$extension")
                file.outputStream().use { it.write(ByteArray(limit + 1)) }
                val error = assertFails { readSkillImport(PlatformFile(file)) }
                assertTrue(error.message.orEmpty().contains("too large"))
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `unsafe paths are rejected even outside the manifest prefix`() {
        for (name in listOf("../escape", "/absolute", "wrapper/../../escape", "a\\b", "C:/escape", "a//b", "./asset")) {
            assertFails { readSkillZip(zip("wrapper/SKILL.md" to manifest, name to byteArrayOf(1))) }
        }
    }

    @Test
    fun `duplicate file entries and file directory aliases are rejected`() {
        val duplicate = zip("first.md" to manifest, "other.md" to manifest)
            .toString(Charsets.ISO_8859_1).replace("other.md", "first.md").toByteArray(Charsets.ISO_8859_1)
        assertFails { readSkillZip(duplicate) }
        assertFails { readSkillZip(zip("assets/" to byteArrayOf(), "assets" to byteArrayOf(1))) }
    }

    @Test
    fun `empty malformed truncated and corrupted archives fail`() {
        for (bytes in listOf(byteArrayOf(), "not a zip".encodeToByteArray(), zip(), zip("empty/" to byteArrayOf()))) {
            assertFails { readSkillZip(bytes) }
        }
        val valid = zip("SKILL.md" to manifest)
        assertFails { readSkillZip(valid.copyOf(valid.size - 1)) }
        val corrupted = valid.copyOf()
        val dataStart = 30 + "SKILL.md".length
        corrupted[dataStart] = (corrupted[dataStart].toInt() xor 0xff).toByte()
        assertFails { readSkillZip(corrupted) }
    }

    @Test
    fun `entry count includes directories and ignored files`() {
        val directories = (0 until MAX_SKILL_FILES).map { "dir-$it/" to byteArrayOf() }
        assertFails { readSkillZip(zip(*(directories + ("SKILL.md" to manifest)).toTypedArray())) }
    }

    @Test
    fun `expanded byte limits include ignored files and directory payloads`() {
        assertFails { readSkillZip(zip("SKILL.md" to manifest, "large" to ByteArray(MAX_SKILL_FILE_BYTES + 1))) }
        assertFails { readSkillZip(zip("SKILL.md" to manifest, "ignored/" to ByteArray(MAX_SKILL_FILE_BYTES + 1))) }
        val ignored = (0..4).map { "outside-$it" to ByteArray(MAX_SKILL_FILE_BYTES) }
        assertFails { readSkillZip(zip(*(("wrapped/SKILL.md" to manifest).let { listOf(it) } + ignored).toTypedArray())) }
        assertFails { readSkillZip(ByteArray(MAX_SKILL_BYTES + 1)) }
    }

    @Test
    fun `manifest presence uniqueness and UTF8 are validated after ZIP reading`() {
        assertFails { skillFromFiles(readSkillZip(zip("asset" to byteArrayOf(1)))) }
        assertFails { skillFromFiles(readSkillZip(zip("SKILL.md" to manifest, "nested/SKILL.md" to manifest))) }
        assertFails { skillFromFiles(readSkillZip(zip("SKILL.md" to byteArrayOf(-1)))) }
    }
}
