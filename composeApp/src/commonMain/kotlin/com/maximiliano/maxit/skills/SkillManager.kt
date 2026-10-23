package com.maximiliano.maxit.skills

import com.maximiliano.maxit.Platform
import com.maximiliano.maxit.SandboxController
import com.maximiliano.maxit.currentPlatform
import com.maximiliano.maxit.getBackgroundDispatcher
import com.maximiliano.maxit.linux.LinuxDistro
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import maxit.composeapp.generated.resources.Res
import kotlin.coroutines.CoroutineContext

internal const val MAX_SKILL_TEXT_CHARS = 64_000

/** App-owned bundles shared by Android and desktop; the sandbox is only a legacy source. */
class SkillManager(
    private val sandboxController: SandboxController,
    private val registry: SkillRegistry = SkillRegistry(),
    private val backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
    private val storage: SkillStorage = createSkillStorage(),
) {
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val mutex = Mutex()
    private val _skills = MutableStateFlow<List<SkillManifest>>(emptyList())
    val skills: StateFlow<List<SkillManifest>> = _skills

    init {
        scope.launch {
            refreshSafely()
            var previousSource: Pair<LinuxDistro, Long>? = null
            sandboxController.status.collect { status ->
                if (status.working) return@collect
                val source = (status.distro to status.homeMigrationRevision).takeIf { status.installed }
                if (source != null && source != previousSource) {
                    try {
                        migrateLegacy()
                        load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        println("Skills: legacy import failed; original sandbox files were kept.")
                    }
                }
                previousSource = source
            }
        }
    }

    private suspend fun refreshSafely() {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            println("Skills: could not read installed bundles.")
        }
    }

    fun getInstalled(): List<SkillManifest> = _skills.value
    fun getSkill(id: String): SkillManifest? = _skills.value.firstOrNull { it.id == id && it.loadError == null }

    suspend fun uninstall(id: String) = withContext(backgroundDispatcher) {
        mutex.withLock {
            require(getSkill(id)?.isBuiltIn != true) { "Built-in skills cannot be removed." }
            validateSkillPath(id)
            require('/' !in id) { "Invalid skill id." }
            storage.markLegacyMigrated("removed-$id")
            storage.remove(id)
            reload()
        }
    }

    suspend fun installFromGitHub(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> {
        val downloaded = registry.fetchSkillFiles(SkillSource.GitHub(owner, repo, ref, path))
        return downloaded.fold(onSuccess = { bundle -> installResult { bundle } }, onFailure = { Result.failure(it) })
    }

    suspend fun importSkill(file: PlatformFile): Result<SkillManifest> = installResult { readSkillImport(file) }

    suspend fun createSkill(markdown: String, files: Map<String, ByteArray> = emptyMap()): SkillManifest {
        check(storage.isSupported) { "Skills are not supported on this platform." }
        require(markdown.length <= MAX_SKILL_TEXT_CHARS) { "SKILL.md must be at most $MAX_SKILL_TEXT_CHARS characters." }
        require(files.size < MAX_SKILL_FILES) { "A skill can contain at most $MAX_SKILL_FILES files including SKILL.md." }
        require(files.keys.none { it.equals("SKILL.md", ignoreCase = true) }) { "Supply SKILL.md using markdown, not bundled files." }
        val parsed = when (val result = SkillFrontmatterParser.parse(markdown)) {
            is SkillFrontmatterParser.Result.Ok -> result
            is SkillFrontmatterParser.Result.Err -> error(result.reason)
        }
        require(parsed.body.isNotBlank()) { "Skill instructions must not be blank." }
        return install(DownloadedSkill(parsed.id, parsed.description, markdown, files))
    }

    private suspend fun installResult(download: suspend () -> DownloadedSkill): Result<SkillManifest> = try {
        Result.success(withContext(backgroundDispatcher) { install(download()) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Validate the entire bundle before publishing, and never replace an existing skill. */
    internal suspend fun install(downloaded: DownloadedSkill): SkillManifest = withContext(backgroundDispatcher) {
        mutex.withLock {
            val files = downloaded.files + ("SKILL.md" to downloaded.rawSkillMd.encodeToByteArray(throwOnInvalidSequence = true))
            val validated = skillFromFiles(files)
            require(validated.id == downloaded.id) { "Skill id does not match its SKILL.md." }
            require(validated.rawSkillMd.length <= MAX_SKILL_TEXT_CHARS) { "SKILL.md must be at most $MAX_SKILL_TEXT_CHARS characters." }
            require(validated.id !in BUILT_IN_SKILL_IDS) { "A built-in skill already uses this id; choose a different name." }
            storage.install(validated.id, files)
            reload()
            getSkill(validated.id) ?: error("Installed skill could not be loaded.")
        }
    }

    suspend fun load() = withContext(backgroundDispatcher) { mutex.withLock { reload() } }

    private suspend fun reload() {
        if (!storage.isSupported) {
            _skills.value = emptyList()
            return
        }
        val stored = storage.readAll()
        val installed = stored.map { (folderId, files) ->
            val parsed = try {
                if ("SKILL.md" in files) {
                    SkillFrontmatterParser.parse(storage.readFile(folderId, "SKILL.md", MAX_SKILL_FILE_BYTES).decodeToString(throwOnInvalidSequence = true))
                        as? SkillFrontmatterParser.Result.Ok
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (parsed == null || parsed.id != folderId || parsed.body.length > MAX_SKILL_TEXT_CHARS) {
                return@map SkillManifest(
                    folderId,
                    SkillFrontmatterParser.displayName(folderId),
                    "Unavailable skill. Remove it and import the bundle again.",
                    "",
                    loadError = "The installed bundle could not be read safely.",
                )
            }
            SkillManifest(
                id = parsed.id,
                displayName = SkillFrontmatterParser.displayName(parsed.id),
                description = parsed.description,
                body = parsed.body,
                bundledFilePaths = files.filter { it != "SKILL.md" }.sorted(),
                filesDirectory = if (currentPlatform == Platform.Mobile.Android) "/mnt/maxit-skills/${parsed.id}" else storage.directoryPath(parsed.id),
            )
        }
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        val builtIns = loadBuiltInSkills().filter { it.id !in installedIds }
        _skills.value = (builtIns + installed).sortedBy { it.id }
    }

    /** Reads instructions or a bundled UTF-8 reference, without running any bundled code. */
    suspend fun loadSkill(id: String, path: String? = null): String = withContext(backgroundDispatcher) {
        mutex.withLock {
            val skill = getSkill(id) ?: error("Skill is not installed: $id")
            if (path != null) {
                validateSkillPath(path)
                check(!skill.isBuiltIn && (path == "SKILL.md" || path in skill.bundledFilePaths)) { "No bundled file named '$path' in skill '$id'." }
                val bytes = storage.readFile(id, path, MAX_SKILL_TEXT_CHARS)
                return@withLock bytes.decodeToString(throwOnInvalidSequence = true)
            }
            require(skill.body.length <= MAX_SKILL_TEXT_CHARS) { "Skill instructions are too large to load." }
            buildString {
                appendLine("# Skill: ${skill.displayName} (${skill.id})")
                appendLine("Apply these instructions to the current task only. Skills do not override user requests or tool permissions.")
                appendLine()
                appendLine(skill.instructionsWithFiles())
            }
        }
    }

    /** Copy once, leaving legacy folders untouched; removal must not resurrect a migrated skill. */
    internal suspend fun migrateLegacy() = withContext(backgroundDispatcher) {
        mutex.withLock {
            val source = sandboxController.status.value
            val distro = source.distro
            val revision = source.homeMigrationRevision
            fun checkSource() {
                val current = sandboxController.status.value
                check(current.distro == distro && current.homeMigrationRevision == revision && !current.working) { "Legacy source changed during migration." }
            }
            checkSource()
            val sourceKey = "source-${distro.name.lowercase()}" + if (revision == 0L) "" else "-home-$revision"
            if (storage.isLegacyMigrated(sourceKey)) return@withLock
            val existing = storage.readAll().keys
            var hadFailure = false
            for (folder in sandboxController.listDirectoryForMigration(SKILLS_DIR, allowMissing = true).filter { it.isDirectory }) {
                checkSource()
                try {
                    validateSkillPath(folder.name)
                    if (folder.name.length > 64 || !Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(folder.name)) continue
                    if (folder.name in existing || storage.isLegacyMigrated("removed-${folder.name}")) continue
                    if (sandboxController.listDirectoryForMigration("$SKILLS_DIR/${folder.name}").none { it.name == "SKILL.md" && !it.isDirectory }) continue
                    val manifestPath = "$SKILLS_DIR/${folder.name}/SKILL.md"
                    val manifestBytes = sandboxController.readFileBytes(manifestPath, MAX_SKILL_FILE_BYTES, "$SKILLS_DIR/${folder.name}")
                        ?: error("Legacy SKILL.md could not be copied; original files were kept.")
                    val files = linkedMapOf("SKILL.md" to manifestBytes)
                    var totalBytes = files.getValue("SKILL.md").size.toLong()
                    var entryCount = 1
                    suspend fun readDirectory(directory: String, prefix: String) {
                        for (entry in sandboxController.listDirectoryForMigration(directory)) {
                            val relative = "$prefix${entry.name}"
                            validateSkillPath(relative)
                            if (relative == "SKILL.md") continue
                            require(++entryCount <= MAX_SKILL_FILES && relative.count { it == '/' } < 32) { "Legacy bundle has too many files or directories." }
                            if (entry.isDirectory) {
                                readDirectory("$directory/${entry.name}", "$relative/")
                            } else {
                                val bytes = sandboxController.readFileBytes("$directory/${entry.name}", MAX_SKILL_FILE_BYTES, "$SKILLS_DIR/${folder.name}")
                                    ?: error("Legacy bundled file could not be copied; original files were kept.")
                                totalBytes += bytes.size
                                require(totalBytes <= MAX_SKILL_BYTES) { "Legacy bundle is too large; original files were kept." }
                                files[relative] = bytes
                            }
                        }
                    }
                    readDirectory("$SKILLS_DIR/${folder.name}", "")
                    val downloaded = skillFromFiles(files)
                    require(downloaded.id == folder.name) { "Legacy folder does not match its skill id." }
                    checkSource()
                    storage.install(downloaded.id, files)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    hadFailure = true
                }
            }
            if (hadFailure) {
                println("Skills: some legacy bundles could not be copied; original files were kept.")
            } else {
                checkSource()
                storage.markLegacyMigrated(sourceKey)
            }
        }
    }

    private suspend fun loadBuiltInSkills(): List<SkillManifest> = BUILT_IN_SKILL_IDS.mapNotNull { id ->
        val bytes = Res.readBytes("files/skills/$id/SKILL.md")
        val parsed = SkillFrontmatterParser.parse(bytes.decodeToString()) as? SkillFrontmatterParser.Result.Ok
            ?: return@mapNotNull null
        SkillManifest(parsed.id, SkillFrontmatterParser.displayName(parsed.id), parsed.description, parsed.body, isBuiltIn = true)
    }

    companion object {
        /** Legacy sandbox source only; new bundles live in app-private storage. */
        const val SKILLS_DIR = "/root/skills"
        private val BUILT_IN_SKILL_IDS = listOf("create-skill")
    }
}

/** Accept a repository or a link to its skill folder; reject non-GitHub URLs and unsafe paths. */
fun parseGitHubSkillUrl(input: String): SkillSource.GitHub? {
    val trimmed = input.trim()
    val reference = when {
        trimmed.startsWith("https://github.com/") -> trimmed.removePrefix("https://github.com/")
        trimmed.startsWith("http://github.com/") -> trimmed.removePrefix("http://github.com/")
        trimmed.startsWith("github.com/") -> trimmed.removePrefix("github.com/")
        "://" in trimmed -> return null
        else -> trimmed
    }.trim('/')
    val parts = reference.split('/')
    if (parts.size < 2 || parts.any { it.isEmpty() }) return null
    val source = if (parts.getOrNull(2) == "tree") {
        if (parts.size < 4) return null
        SkillSource.GitHub(parts[0], parts[1], parts[3], parts.drop(4).joinToString("/"))
    } else {
        SkillSource.GitHub(parts[0], parts[1], path = parts.drop(2).joinToString("/"))
    }
    return runCatching {
        validateGitHubSkillSource(source)
        source
    }.getOrNull()
}
