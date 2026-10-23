package com.maximiliano.maxit.skills

import com.maximiliano.maxit.data.SharedJson
import com.maximiliano.maxit.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Downloads a single instruction bundle from a public GitHub repository. */
class SkillRegistry(
    private val client: HttpClient = httpClient(),
    private val json: Json = SharedJson,
) {
    suspend fun fetchSkillFiles(source: SkillSource.GitHub): Result<DownloadedSkill> = try {
        validateGitHubSkillSource(source)
        val (owner, repo, ref, path) = source
        val encodedRef = ref.encodeURLPath(encodeSlash = true)
        val treeBytes = client.prepareGet("https://api.github.com/repos/$owner/$repo/git/trees/$encodedRef?recursive=1") {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }.execute { it.readBounded() }
        val root = json.parseToJsonElement(treeBytes.decodeToString()).jsonObject
        require(root["truncated"]?.jsonPrimitive?.content != "true") { "Repository file list is too large; import a skill ZIP instead." }
        val tree = root["tree"] as? JsonArray ?: error("GitHub did not return a repository file list.")
        val prefix = if (path.isEmpty()) "" else "$path/"
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        for (entry in tree) {
            val obj = entry.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "blob") continue
            val repoPath = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!repoPath.startsWith(prefix)) continue
            val relative = repoPath.removePrefix(prefix)
            validateSkillPath(relative)
            require(obj["mode"]?.jsonPrimitive?.contentOrNull != "120000") { "Skill bundles cannot contain symbolic links." }
            require(files.size < MAX_SKILL_FILES) { "Too many bundled files; use a GitHub link to a single skill folder." }
            val encodedPath = repoPath.encodeURLPath()
            val bytes = client.prepareGet("https://raw.githubusercontent.com/$owner/$repo/$encodedRef/$encodedPath").execute { it.readBounded() }
            total += bytes.size
            require(total <= MAX_SKILL_BYTES) { "Skill bundle is too large (maximum 16 MiB)." }
            files[relative] = bytes
        }
        Result.success(skillFromFiles(files))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun HttpResponse.readBounded(): ByteArray {
        require(status.isSuccess()) { "GitHub download failed (HTTP ${status.value}). Check the repository, branch, and skill folder." }
        val bytes = bodyAsChannel().readRemaining(MAX_SKILL_FILE_BYTES.toLong() + 1).readByteArray()
        require(bytes.size <= MAX_SKILL_FILE_BYTES) { "GitHub file is too large (maximum 4 MiB)." }
        return bytes
    }
}

internal fun validateGitHubSkillSource(source: SkillSource.GitHub) {
    require(Regex("[A-Za-z0-9-]+").matches(source.owner)) { "Invalid GitHub owner." }
    require(Regex("[A-Za-z0-9_.-]+").matches(source.repo) && source.repo != "." && source.repo != "..") { "Invalid GitHub repository." }
    validateSkillPath(source.ref)
    if (source.path.isNotEmpty()) validateSkillPath(source.path)
    require((source.ref + source.path).none { it.isWhitespace() || it == '?' || it == '#' || it == '%' }) { "Invalid GitHub branch or folder." }
}
