// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.skills

/**
 * Tiny YAML-subset parser for SKILL.md frontmatter. Only handles `name` and
 * `description` fields — anything else in the frontmatter is ignored. Mirrors
 * Anthropic's validation rules (id ≤ 64 chars, lowercase letters/digits/hyphens
 * only; description ≤ 1024 chars, non-empty).
 */
object SkillFrontmatterParser {

    private val idRegex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    sealed class Result {
        data class Ok(val id: String, val description: String, val body: String) : Result()
        data class Err(val reason: String) : Result()
    }

    fun parse(source: String): Result {
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").trimStart()
        if (!normalized.startsWith("---\n")) {
            return Result.Err("Missing frontmatter (must start with '---').")
        }
        val afterOpen = normalized.removePrefix("---\n")
        val close = Regex("(?m)^---[ \\t]*$").find(afterOpen)
        if (close == null) {
            return Result.Err("Frontmatter not closed (expected a second '---').")
        }
        val frontmatter = afterOpen.substring(0, close.range.first)
        val body = afterOpen.substring(close.range.last + 1).trimStart('\n')

        var name: String? = null
        var description: String? = null
        val lines = frontmatter.split('\n')
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.startsWith("#") || line.first().isWhitespace()) continue
            val sepIdx = line.indexOf(':')
            if (sepIdx <= 0) continue
            val key = line.substring(0, sepIdx).trim()
            val value = line.substring(sepIdx + 1).trim().trim('"', '\'')
            when (key) {
                "name" -> name = value

                "description" -> {
                    description = if (value in setOf(">", ">-", ">+", "|", "|-", "|+")) {
                        val block = lines.subList(index + 1, lines.size).takeWhile { it.isBlank() || it.first().isWhitespace() }
                        block.joinToString(if (value.startsWith('>')) " " else "\n") { it.trim() }.trim()
                    } else {
                        value
                    }
                }
            }
        }

        val id = name ?: return Result.Err("Missing 'name' in frontmatter.")
        if (id.length > 64) return Result.Err("'name' must be ≤ 64 characters.")
        if (!idRegex.matches(id)) return Result.Err("'name' must be lowercase letters, digits, and hyphens only.")

        val desc = description ?: return Result.Err("Missing 'description' in frontmatter.")
        if (desc.isEmpty()) return Result.Err("'description' must be non-empty.")
        if (desc.length > 1024) return Result.Err("'description' must be ≤ 1024 characters.")

        return Result.Ok(id, desc, body)
    }

    fun displayName(id: String): String = id.split('-').joinToString(" ") { part ->
        part.replaceFirstChar { it.titlecase() }
    }
}
