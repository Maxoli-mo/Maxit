package com.maximiliano.maxit.tools

import com.maximiliano.maxit.network.tools.ParameterSchema
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.network.tools.ToolInfo
import com.maximiliano.maxit.network.tools.ToolSchema
import com.maximiliano.maxit.skills.MAX_SKILL_BYTES
import com.maximiliano.maxit.skills.MAX_SKILL_FILES
import com.maximiliano.maxit.skills.MAX_SKILL_FILE_BYTES
import com.maximiliano.maxit.skills.MAX_SKILL_TEXT_CHARS
import com.maximiliano.maxit.skills.SkillManager
import com.maximiliano.maxit.skills.validateSkillPath
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.tool_create_skill_description
import maxit.composeapp.generated.resources.tool_create_skill_name
import maxit.composeapp.generated.resources.tool_load_skill_description
import maxit.composeapp.generated.resources.tool_load_skill_name
import kotlin.io.encoding.Base64

/** Read or create installed bundles; never run their scripts. */
object SkillTools {
    const val ID = "load_skill"
    const val CREATE_ID = "create_skill"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Load Skill",
        description = "Read an installed skill or one of its bundled files",
        nameRes = Res.string.tool_load_skill_name,
        descriptionRes = Res.string.tool_load_skill_description,
        userToggleable = false,
    )

    val creationToolInfo = ToolInfo(
        id = CREATE_ID,
        name = "Create Skill",
        description = "Validate and install a new skill requested by the user",
        nameRes = Res.string.tool_create_skill_name,
        descriptionRes = Res.string.tool_create_skill_description,
        userToggleable = false,
    )

    fun createSkill(skillManager: SkillManager): Tool = object : Tool {
        override val schema = ToolSchema(
            name = CREATE_ID,
            description = "Create and install a new reusable skill only when the user explicitly asks to create or save a skill. " +
                "Load the built-in create-skill guide and self-review the instructions and all files before saving. " +
                "Supply raw SKILL.md Markdown with YAML name and description frontmatter, without outer code fences. " +
                "Maximum 64,000 Markdown characters, 4 MiB per file, 16 MiB total, and 256 files including SKILL.md. " +
                "Existing ids and built-ins cannot be overwritten. Saving does not run code, install dependencies, or verify behavior.",
            parameters = mapOf(
                "markdown" to ParameterSchema("string", "Complete SKILL.md content, including name and description frontmatter and the instruction body", required = true),
                "files" to ParameterSchema(
                    type = "array",
                    description = "Optional bundled files with relative paths and literal contents",
                    required = false,
                    rawSchema = buildJsonObject {
                        put("type", "array")
                        put("description", "Optional bundled scripts, references, templates, or binary assets. Omit for instructions only.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Safe relative path inside the skill, never SKILL.md, an absolute path, or a parent path")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "Literal UTF-8 text by default, or standard base64 for a binary asset; never a host file path")
                                }
                                putJsonObject("encoding") {
                                    put("type", "string")
                                    put("description", "Omit or use utf8 for text; use base64 only for known binary bytes")
                                    putJsonArray("enum") {
                                        add("utf8")
                                        add("base64")
                                    }
                                }
                            }
                            putJsonArray("required") {
                                add("path")
                                add("content")
                            }
                            put("additionalProperties", false)
                        }
                    },
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val markdown = args["markdown"] as? String
            require(!markdown.isNullOrBlank()) { "markdown must be non-empty SKILL.md content" }
            require(markdown.length <= MAX_SKILL_TEXT_CHARS) { "SKILL.md must be at most $MAX_SKILL_TEXT_CHARS characters." }
            val files = bundledFiles(args["files"], MAX_SKILL_BYTES - markdown.encodeToByteArray(throwOnInvalidSequence = true).size)
            val skill = skillManager.createSkill(markdown, files)
            return mapOf("success" to true, "id" to skill.id, "command" to "/${skill.id}", "description" to skill.description, "bundled_file_count" to skill.bundledFilePaths.size)
        }
    }

    internal fun localSchema(schema: ToolSchema): ToolSchema = if (schema.name == CREATE_ID) {
        // Small on-device models retain the original string-only creation call.
        schema.copy(
            description = "Create an instruction-only skill at the user's explicit request. Read create-skill and self-review first. " +
                "Supply raw SKILL.md with name/description frontmatter and non-empty instructions, at most 64,000 characters. " +
                "Bundled files are not offered by this local schema; use a remote model or import a ZIP if they are needed. " +
                "Existing skills cannot be overwritten. Saving does not execute or test the skill.",
            parameters = schema.parameters.filterKeys { it == "markdown" },
        )
    } else {
        schema
    }

    private fun bundledFiles(value: Any?, maxBytes: Int): Map<String, ByteArray> {
        if (value == null) return emptyMap()
        require(value is List<*>) { "files must be an array of file objects." }
        require(value.size < MAX_SKILL_FILES) { "A skill can contain at most $MAX_SKILL_FILES files including SKILL.md." }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        for (entry in value) {
            require(entry is Map<*, *> && entry.keys.all { it in setOf("path", "content", "encoding") }) { "Each file must contain path, content, and optional encoding only." }
            val path = entry["path"] as? String ?: error("Each bundled file requires a string path.")
            validateSkillPath(path)
            require(!path.equals("SKILL.md", ignoreCase = true)) { "Supply SKILL.md using markdown, not bundled files." }
            require(path !in files) { "Duplicate bundled file: $path" }
            val content = entry["content"] as? String ?: error("Each bundled file requires string content.")
            val bytes = when (entry["encoding"] ?: "utf8") {
                "utf8" -> {
                    require(content.length <= MAX_SKILL_FILE_BYTES) { "Bundled file is too large: $path" }
                    content.encodeToByteArray(throwOnInvalidSequence = true)
                }

                "base64" -> {
                    require(content.length <= ((MAX_SKILL_FILE_BYTES + 2) / 3) * 4) { "Bundled file is too large: $path" }
                    try {
                        Base64.decode(content)
                    } catch (_: IllegalArgumentException) {
                        throw IllegalArgumentException("Invalid base64 content for bundled file: $path")
                    }
                }

                else -> error("Bundled file encoding must be utf8 or base64.")
            }
            require(bytes.size <= MAX_SKILL_FILE_BYTES) { "Bundled file is too large: $path" }
            total += bytes.size
            require(total <= maxBytes) { "Skill bundle is too large (maximum 16 MiB including SKILL.md)." }
            files[path] = bytes
        }
        return files
    }

    fun create(skillManager: SkillManager): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Load the full instructions for an installed skill before doing a matching task. " +
                "Provide path to read a bundled file listed by the skill. This only reads content; " +
                "it does not install anything or execute scripts.",
            parameters = mapOf(
                "id" to ParameterSchema("string", "Installed skill id from the skills catalog", required = true),
                "path" to ParameterSchema("string", "Optional relative bundled file path listed by the skill", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val id = args["id"] as? String
            require(!id.isNullOrBlank()) { "id must be a non-empty skill id" }
            val path = args["path"]
            require(path == null || path is String) { "path must be a string" }
            return skillManager.loadSkill(id, path)
        }
    }
}
