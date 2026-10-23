// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.skills

/**
 * An installed instruction bundle, stored in app-private files independently of
 * the Android sandbox. Bundled files are read on demand through the skills tool.
 *
 * [isBuiltIn] marks skills that ship inside the app (loaded from compose resources
 * rather than user storage). They are always available,
 * cannot be uninstalled, and update with each app release.
 */
data class SkillManifest(
    val id: String,
    val displayName: String,
    val description: String,
    val body: String,
    val bundledFilePaths: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val loadError: String? = null,
    val filesDirectory: String? = null,
)

/** Keep explicit activation and on-demand loading consistent about installed assets. */
internal fun SkillManifest.instructionsWithFiles(): String = buildString {
    append(body.trim())
    if (isBuiltIn) return@buildString
    append("\n\nInstalled files (relative to the skill directory):\n- SKILL.md\n")
    bundledFilePaths.sorted().forEach { appendLine("- $it") }
    if (bundledFilePaths.isEmpty()) {
        appendLine("This is an instructions-only installation (SKILL.md only). If the instructions require fonts, scripts, or other support files, those files are not installed. To replace this installation, preserve any customized SKILL.md, remove the skill through Settings, then import the complete ZIP or GitHub skill folder; existing skill ids cannot be overwritten.")
    }
    appendLine("Read bundled UTF-8 text with load_skill, id=\"$id\", path=<listed relative path>. Binary assets such as fonts and images must be used through an enabled filesystem/shell tool, not load_skill.")
    if (filesDirectory != null) {
        appendLine("Files directory: $filesDirectory")
        appendLine("Resolve relative asset and script paths in these instructions against this directory, not the shell's current directory. Use absolute paths under it, or explicitly set working_dir to it when needed. Keep generated outputs in the user's requested working/output directory, not the installed skill folder.")
        appendLine("On Android this directory is available only inside an installed Linux sandbox. On desktop it is a host directory; shell commands run with the user's permissions, not in a sandbox.")
    }
    append("Only the listed files are installed. Report missing required assets rather than assuming they exist or silently downloading replacements. Installing or loading a skill never executes scripts or installs dependencies; use an enabled shell only within the user's task and permissions, and invoke scripts through their interpreter.")
}

/** Where a skill is downloaded from. */
sealed class SkillSource {
    /** An arbitrary GitHub repo + folder path containing a `SKILL.md`. */
    data class GitHub(
        val owner: String,
        val repo: String,
        val ref: String = "main",
        val path: String,
    ) : SkillSource()
}

/**
 * The raw files of a skill downloaded from GitHub, ready to be written into the
 * app-private storage. [rawSkillMd] is stored verbatim as `SKILL.md`;
 * [files] maps each sibling's relative path to its bytes, including binary assets.
 */
data class DownloadedSkill(
    val id: String,
    val description: String,
    val rawSkillMd: String,
    val files: Map<String, ByteArray> = emptyMap(),
)
