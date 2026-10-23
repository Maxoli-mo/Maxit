package com.maximiliano.maxit.tools

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.LOCAL_TOOL_ALLOWLIST
import com.maximiliano.maxit.data.MemoryStore
import com.maximiliano.maxit.data.TaskStore
import com.maximiliano.maxit.data.ToolExecutor
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.skills.DownloadedSkill
import com.maximiliano.maxit.skills.MAX_SKILL_FILES
import com.maximiliano.maxit.skills.MAX_SKILL_FILE_BYTES
import com.maximiliano.maxit.skills.SkillManager
import com.maximiliano.maxit.testutil.FakeSandboxController
import com.maximiliano.maxit.testutil.FakeSkillStorage
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SkillToolsTest {
    private suspend fun manager(): SkillManager = SkillManager(
        sandboxController = FakeSandboxController(installed = false),
        backgroundDispatcher = UnconfinedTestDispatcher(),
        storage = FakeSkillStorage(),
    ).apply {
        install(
            DownloadedSkill(
                id = "test-skill",
                description = "Test instructions",
                rawSkillMd = "---\nname: test-skill\ndescription: Test instructions\n---\nFollow these test instructions.",
                files = mapOf(
                    "references/guide.md" to "Reference text.".encodeToByteArray(),
                    "scripts/helper.py" to "print('not executed')".encodeToByteArray(),
                ),
            ),
        )
    }

    @Test
    fun `loader returns complete instructions and bundled file paths without a sandbox`() = runTest {
        val tool = SkillTools.create(manager())
        assertEquals("load_skill", tool.schema.name)
        assertEquals(setOf("id", "path"), tool.schema.parameters.keys)
        assertTrue(tool.schema.parameters.getValue("id").required)
        assertFalse(tool.schema.parameters.getValue("path").required)
        val instructions = tool.execute(mapOf("id" to "test-skill")).toString()
        assertTrue("Follow these test instructions." in instructions)
        assertTrue("references/guide.md" in instructions)
        assertTrue("scripts/helper.py" in instructions)
    }

    @Test
    fun `loader reads nested references and returns script source without running it`() = runTest {
        val tool = SkillTools.create(manager())
        assertEquals("Reference text.", tool.execute(mapOf("id" to "test-skill", "path" to "references/guide.md")))
        assertEquals("print('not executed')", tool.execute(mapOf("id" to "test-skill", "path" to "scripts/helper.py")))
    }

    @Test
    fun `loader rejects missing or wrongly typed arguments`() = runTest {
        val tool = SkillTools.create(manager())
        for (args in listOf(emptyMap(), mapOf("id" to ""), mapOf("id" to " "), mapOf("id" to 42))) {
            assertFailsWith<IllegalArgumentException> { tool.execute(args) }
        }
        assertFailsWith<IllegalArgumentException> { tool.execute(mapOf("id" to "test-skill", "path" to 42)) }
    }

    @Test
    fun `loader rejects unknown ids missing files and traversal`() = runTest {
        val tool = SkillTools.create(manager())
        assertFailsWith<IllegalStateException> { tool.execute(mapOf("id" to "not-installed")) }
        assertFailsWith<IllegalStateException> { tool.execute(mapOf("id" to "test-skill", "path" to "missing.txt")) }
        for (path in listOf("", "../secret", "/etc/passwd", "references/../../secret", "references\\guide.md")) {
            assertFailsWith<IllegalArgumentException> { tool.execute(mapOf("id" to "test-skill", "path" to path)) }
        }
    }

    @Test
    fun `executor reports loader validation errors as tool results`() = runTest {
        val tool = SkillTools.create(manager())
        val result = ToolExecutor { listOf(tool) }.executeTool(SkillTools.ID, """{"id":"not-installed"}""")
        assertTrue("Tool execution failed" in result)
        assertTrue("not-installed" in result)
    }

    @Test
    fun `creator installs immediately without sandbox and returns the actual installed id`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val markdown = "---\nname: meeting-notes\ndescription: Summarize meetings\n---\nList decisions and action items."
        assertEquals(SkillTools.CREATE_ID, creator.schema.name)
        assertEquals(setOf("markdown", "files"), creator.schema.parameters.keys)
        assertTrue(creator.schema.parameters.getValue("markdown").required)
        assertFalse(creator.schema.parameters.getValue("files").required)
        val result = creator.execute(mapOf("markdown" to markdown)) as Map<*, *>
        assertEquals(true, result["success"])
        assertEquals("meeting-notes", result["id"])
        assertEquals("/meeting-notes", result["command"])
        assertTrue(manager.skills.value.any { it.id == "meeting-notes" })
        assertTrue("List decisions and action items." in SkillTools.create(manager).execute(mapOf("id" to "meeting-notes")).toString())
        assertEquals(0, result["bundled_file_count"])
    }

    @Test
    fun `creator accepts nested file arguments through the executor without executing code`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val result = ToolExecutor { listOf(creator) }.executeTool(
            SkillTools.CREATE_ID,
            """{"markdown":"---\nname: bundled\ndescription: Example\n---\nRead the reference before running scripts/helper.py.","files":[{"path":"scripts/helper.py","content":"print('ok')\n"},{"path":"references/guide.md","content":"Reference \uD83D\uDE00 text.","encoding":"utf8"},{"path":"assets/icon.bin","content":"AP8B","encoding":"base64"}]}""",
        )
        val response = Json.parseToJsonElement(result).jsonObject
        assertEquals("true", response.getValue("success").jsonPrimitive.content)
        assertEquals(3, response.getValue("bundled_file_count").jsonPrimitive.int)
        assertEquals("print('ok')\n", manager.loadSkill("bundled", "scripts/helper.py"))
        assertEquals("Reference \uD83D\uDE00 text.", manager.loadSkill("bundled", "references/guide.md"))
        assertEquals(listOf("assets/icon.bin", "references/guide.md", "scripts/helper.py"), manager.getSkill("bundled")?.bundledFilePaths)
    }

    @Test
    fun `creator preserves decoded binary bytes`() = runTest {
        val storage = FakeSkillStorage()
        val manager = SkillManager(FakeSandboxController(installed = false), backgroundDispatcher = UnconfinedTestDispatcher(), storage = storage)
        SkillTools.createSkill(manager).execute(
            mapOf(
                "markdown" to "---\nname: binary\ndescription: Binary example\n---\nUse the asset.",
                "files" to listOf(mapOf("path" to "assets/data.bin", "content" to "AP8B", "encoding" to "base64")),
            ),
        )
        assertContentEquals(byteArrayOf(0, -1, 1), storage.bundles.getValue("binary").getValue("assets/data.bin"))
    }

    @Test
    fun `malformed Unicode in manifests text or paths is rejected without installation`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val markdown = "---\nname: malformed\ndescription: Example\n---\nInstructions."
        val originalIds = manager.getInstalled().map { it.id }
        for (malformed in listOf("\uD800", "\uDC00")) {
            assertFailsWith<CharacterCodingException> { creator.execute(mapOf("markdown" to markdown + malformed)) }
            assertFailsWith<CharacterCodingException> {
                creator.execute(mapOf("markdown" to markdown, "files" to listOf(mapOf("path" to "reference.md", "content" to malformed))))
            }
            assertFailsWith<CharacterCodingException> {
                creator.execute(mapOf("markdown" to markdown, "files" to listOf(mapOf("path" to "bad-$malformed.txt", "content" to "Valid text."))))
            }
            assertEquals(originalIds, manager.getInstalled().map { it.id })
        }
    }

    @Test
    fun `invalid file structures encodings duplicates and paths never create a partial skill`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val originalIds = manager.getInstalled().map { it.id }
        val file = mapOf("path" to "references/a.md", "content" to "Text")
        val invalid = listOf(
            "not an array",
            listOf("not an object"),
            listOf(null),
            listOf(mapOf("content" to "No path")),
            listOf(mapOf("path" to "a.md")),
            listOf(file + ("path" to 42)),
            listOf(file + ("content" to 42)),
            listOf(file + ("encoding" to "zip")),
            listOf(file + mapOf("encoding" to "base64", "content" to "not@base64")),
            listOf(file + ("unknown" to true)),
            listOf(file, file),
            listOf(file, file + ("path" to "SKILL.md")),
            listOf(file, file + ("path" to "skill.md")),
            listOf(file, file + ("path" to "../escape")),
            listOf(file, file + ("path" to "/absolute")),
            listOf(file, file + ("path" to "a\\b")),
            List(MAX_SKILL_FILES) { file + ("path" to "$it.txt") },
        )
        for (files in invalid) {
            assertFails { creator.execute(mapOf("markdown" to "---\nname: invalid\ndescription: Example\n---\nInstructions.", "files" to files)) }
            assertEquals(originalIds, manager.getInstalled().map { it.id })
        }
    }

    @Test
    fun `decoded byte bounds include UTF8 binary data and the manifest`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val markdown = "---\nname: oversized\ndescription: Example\n---\nInstructions."
        val oversized = listOf(
            listOf(mapOf("path" to "large.txt", "content" to "a".repeat(MAX_SKILL_FILE_BYTES + 1))),
            listOf(mapOf("path" to "unicode.txt", "content" to "€".repeat(MAX_SKILL_FILE_BYTES / 3 + 1))),
            listOf(mapOf("path" to "large.bin", "content" to "A".repeat(((MAX_SKILL_FILE_BYTES + 2) / 3) * 4 + 4), "encoding" to "base64")),
            List(4) { mapOf("path" to "$it.txt", "content" to "a".repeat(MAX_SKILL_FILE_BYTES)) },
        )
        for (files in oversized) {
            assertFails { creator.execute(mapOf("markdown" to markdown, "files" to files)) }
            assertTrue(manager.getSkill("oversized") == null)
        }
    }

    @Test
    fun `bundle schema retains nested properties and local schema preserves string-only compatibility`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val raw = creator.schema.parameters.getValue("files").rawSchema!!
        assertEquals("array", raw.getValue("type").jsonPrimitive.content)
        val item = raw.getValue("items").jsonObject
        assertEquals(listOf("path", "content"), item.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("utf8", "base64"), item.getValue("properties").jsonObject.getValue("encoding").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        val local = SkillTools.localSchema(creator.schema)
        assertEquals(setOf("markdown"), local.parameters.keys)
        assertTrue("instruction-only" in local.description)
        assertTrue("files" in creator.schema.parameters)
        val reader = SkillTools.create(manager)
        assertTrue(SkillTools.localSchema(reader.schema) === reader.schema)
    }

    @Test
    fun `built-in guide requires self-review and its bundled example creates a complete skill`() = runTest {
        val manager = manager()
        val guide = manager.loadSkill("create-skill")
        for (check in listOf("## Pre-save self-review", "File references", "Tool and runtime availability", "Example walkthrough", "not an independent audit")) {
            assertTrue(check in guide, "Missing creation review guidance: $check")
        }
        val example = guide.substringAfter("```json\n").substringBefore("\n```")
        val creator = SkillTools.createSkill(manager)
        val result = ToolExecutor { listOf(creator) }.executeTool(SkillTools.CREATE_ID, example)
        assertEquals("true", Json.parseToJsonElement(result).jsonObject.getValue("success").jsonPrimitive.content)
        assertEquals(listOf("references/output.md"), manager.getSkill("meeting-notes")?.bundledFilePaths)
    }

    @Test
    fun `creator rejects missing wrong typed malformed and oversized content`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val originalIds = manager.getInstalled().map { it.id }
        for (args in listOf(emptyMap(), mapOf("markdown" to 42), mapOf("markdown" to " "), mapOf("markdown" to "No frontmatter"), mapOf("markdown" to "x".repeat(64_001)))) {
            assertFails { creator.execute(args) }
            assertEquals(originalIds, manager.getInstalled().map { it.id })
        }
    }

    @Test
    fun `creator cannot overwrite installed or built-in skills or accept unsafe ids`() = runTest {
        val manager = manager()
        val creator = SkillTools.createSkill(manager)
        val originalBody = manager.getSkill("test-skill")?.body
        for (id in listOf("test-skill", "create-skill", "../escape")) {
            assertFails { creator.execute(mapOf("markdown" to "---\nname: $id\ndescription: Replacement\n---\nReplacement body.")) }
        }
        assertEquals(originalBody, manager.getSkill("test-skill")?.body)
        assertTrue(manager.getSkill("create-skill")?.isBuiltIn == true)
    }

    @Test
    fun `creator reports failures through the normal tool error format`() = runTest {
        val creator = SkillTools.createSkill(manager())
        val result = ToolExecutor { listOf(creator) }.executeTool(SkillTools.CREATE_ID, """{"markdown":"invalid"}""")
        assertTrue("\"success\":false" in result)
        assertTrue("frontmatter" in result)
    }

    @Test
    fun `large valid bodies and file listings fit the skill result limit`() = runTest {
        val manager = manager()
        val paths = (0 until 255).associate { index ->
            "references/${"a".repeat(195)}/$index-${"b".repeat(195)}.md" to byteArrayOf(1)
        }
        val body = "x".repeat(63_000) + "INSTRUCTIONS-END"
        manager.install(DownloadedSkill("large", "Test", "---\nname: large\ndescription: Test\n---\n$body", paths))
        val result = ToolExecutor { listOf(SkillTools.create(manager)) }.executeTool(SkillTools.ID, """{"id":"large"}""")
        assertTrue(result.length > 160_000)
        assertTrue("INSTRUCTIONS-END" in result)
        assertTrue(paths.keys.last() in result)
        assertFalse("Tool execution failed" in result)
    }

    @Test
    fun `injected loader is registered even with optional tools disabled and is locally allowed`() = runTest {
        val settings = AppSettings(MapSettings()).apply {
            setMemoryEnabled(false)
            setSchedulingEnabled(false)
            setEmailEnabled(false)
        }
        val tools = buildAgentToolSet(
            appSettings = settings,
            memoryStore = MemoryStore(settings),
            taskStore = TaskStore(settings),
            mcpServerManager = McpServerManager(settings),
            skillManager = manager(),
        )
        assertEquals(1, tools.count { it.schema.name == SkillTools.ID })
        assertEquals(1, tools.count { it.schema.name == SkillTools.CREATE_ID })
        assertTrue(SkillTools.ID in LOCAL_TOOL_ALLOWLIST)
        assertTrue(SkillTools.CREATE_ID in LOCAL_TOOL_ALLOWLIST)
    }
}
