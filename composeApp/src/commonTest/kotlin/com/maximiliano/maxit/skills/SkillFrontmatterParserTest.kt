// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillFrontmatterParserTest {

    @Test
    fun `parses name description and body`() {
        val source = """
            ---
            name: pdf-tools
            description: Extract and manipulate PDF files.
            ---
            Use the bundled script to extract text.
        """.trimIndent()

        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Ok>(result)
        assertEquals("pdf-tools", result.id)
        assertEquals("Extract and manipulate PDF files.", result.description)
        assertEquals("Use the bundled script to extract text.", result.body)
    }

    @Test
    fun `tolerates CRLF and quoted values`() {
        val source = "---\r\nname: \"my-skill\"\r\ndescription: 'A thing'\r\n---\r\nBody line"
        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Ok>(result)
        assertEquals("my-skill", result.id)
        assertEquals("A thing", result.description)
        assertEquals("Body line", result.body)
    }

    @Test
    fun `rejects missing frontmatter`() {
        val result = SkillFrontmatterParser.parse("just some text without frontmatter")
        assertIs<SkillFrontmatterParser.Result.Err>(result)
    }

    @Test
    fun `rejects unclosed frontmatter`() {
        val result = SkillFrontmatterParser.parse("---\nname: x\ndescription: y\n")
        assertIs<SkillFrontmatterParser.Result.Err>(result)
    }

    @Test
    fun `rejects invalid id characters`() {
        val source = "---\nname: Bad_Name\ndescription: ok\n---\nbody"
        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Err>(result)
    }

    @Test
    fun `rejects missing description`() {
        val source = "---\nname: ok-name\n---\nbody"
        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Err>(result)
    }

    @Test
    fun `rejects overlong id`() {
        val longName = "a".repeat(65)
        val result = SkillFrontmatterParser.parse("---\nname: $longName\ndescription: ok\n---\nbody")
        assertIs<SkillFrontmatterParser.Result.Err>(result)
    }

    @Test
    fun `displayName titlecases hyphenated id`() {
        assertEquals("Pdf Tools", SkillFrontmatterParser.displayName("pdf-tools"))
        assertEquals("Single", SkillFrontmatterParser.displayName("single"))
    }

    @Test
    fun `ignores unknown frontmatter keys`() {
        val source = "---\nname: ok-name\nlicense: MIT\ndescription: ok\nversion: 1.0\n---\nbody"
        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Ok>(result)
        assertTrue(result.id == "ok-name")
    }

    @Test
    fun `folded descriptions are usable for automatic discovery`() {
        val source = "---\nname: example\ndescription: >-\n  Work with PDFs.\n  Extract their tables.\nmetadata:\n  name: not-the-id\n---\nInstructions."
        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Ok>(result)
        assertEquals("example", result.id)
        assertEquals("Work with PDFs. Extract their tables.", result.description)
        assertEquals("Instructions.", result.body)
    }

    @Test
    fun `repeated folded description keys use the last block`() {
        val source = buildString {
            append("---\nname: example\n")
            repeat(2_000) { append("description: >-\n  Ignored.\n") }
            append("description: >-\n  Last block.\n  Still here.\n---\nInstructions.")
        }
        val result = SkillFrontmatterParser.parse(source)
        assertIs<SkillFrontmatterParser.Result.Ok>(result)
        assertEquals("Last block. Still here.", result.description)
        assertEquals("Instructions.", result.body)
    }

    @Test
    fun `literal descriptions and UTF8 BOM are supported`() {
        val result = SkillFrontmatterParser.parse("\uFEFF---\nname: example\ndescription: |\n  First line.\n  Second line.\n---\nInstructions.")
        assertIs<SkillFrontmatterParser.Result.Ok>(result)
        assertEquals("First line.\nSecond line.", result.description)
    }

    @Test
    fun `frontmatter closes only at an exact delimiter line`() {
        val result = SkillFrontmatterParser.parse("---\nname: example\ndescription: ok\n---not-a-delimiter\n")
        assertIs<SkillFrontmatterParser.Result.Err>(result)
    }
}
