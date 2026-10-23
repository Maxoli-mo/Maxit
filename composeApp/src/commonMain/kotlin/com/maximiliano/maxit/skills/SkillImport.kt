package com.maximiliano.maxit.skills

/** Bounds apply to both downloaded and imported bundles, before anything is installed. */
internal const val MAX_SKILL_FILE_BYTES = 4 * 1024 * 1024
internal const val MAX_SKILL_BYTES = 16 * 1024 * 1024
internal const val MAX_SKILL_FILES = 256

internal fun validateSkillPath(path: String) {
    require(path.isNotEmpty() && path.length <= 512) { "Invalid skill file path." }
    require(path.none { it == '\\' || it == ':' || it.isISOControl() }) { "Invalid skill file path: $path" }
    require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe skill file path: $path" }
    // Native path conversion must not silently replace malformed Unicode.
    path.encodeToByteArray(throwOnInvalidSequence = true)
}

internal fun skillFromFiles(files: Map<String, ByteArray>): DownloadedSkill {
    require(files.size <= MAX_SKILL_FILES) { "A skill can contain at most $MAX_SKILL_FILES files." }
    var total = 0L
    for ((path, bytes) in files) {
        validateSkillPath(path)
        require(bytes.size <= MAX_SKILL_FILE_BYTES) { "Skill file is too large: $path" }
        total += bytes.size
    }
    require(total <= MAX_SKILL_BYTES) { "Skill bundle is too large (maximum 16 MiB)." }
    val manifests = files.keys.filter { it.substringAfterLast('/') == "SKILL.md" }
    require(manifests.size == 1) { "The bundle must contain exactly one SKILL.md." }
    val manifest = manifests.single()
    val prefix = manifest.removeSuffix("SKILL.md")
    val markdown = files.getValue(manifest).decodeToString(throwOnInvalidSequence = true)
    require(markdown.length <= MAX_SKILL_TEXT_CHARS) { "SKILL.md must be at most $MAX_SKILL_TEXT_CHARS characters." }
    val parsed = when (val result = SkillFrontmatterParser.parse(markdown)) {
        is SkillFrontmatterParser.Result.Ok -> result
        is SkillFrontmatterParser.Result.Err -> error(result.reason)
    }
    return DownloadedSkill(
        id = parsed.id,
        description = parsed.description,
        rawSkillMd = markdown,
        files = files.filterKeys { it.startsWith(prefix) && it != manifest }
            .mapKeys { (path, _) -> path.removePrefix(prefix) },
    )
}

/** Implementations read ZIP entries without extracting paths onto the host filesystem. */
internal expect fun readSkillZip(bytes: ByteArray): Map<String, ByteArray>
