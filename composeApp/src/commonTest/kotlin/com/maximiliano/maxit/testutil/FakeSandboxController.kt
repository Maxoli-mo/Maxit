// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.testutil

import com.maximiliano.maxit.CommandHandle
import com.maximiliano.maxit.NoOpCommandHandle
import com.maximiliano.maxit.SandboxController
import com.maximiliano.maxit.SandboxFileEntry
import com.maximiliano.maxit.SandboxStatus
import com.maximiliano.maxit.TextFileResult
import com.maximiliano.maxit.linux.LinuxDistro
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [SandboxController] for tests. Backs the file ops with a flat
 * path→content map (directories are implicit from path prefixes), which is all
 * [com.maximiliano.maxit.skills.SkillManager] needs to store and read skills.
 */
class FakeSandboxController(installed: Boolean = true) : SandboxController {

    val files = mutableMapOf<String, String>()
    val binaryFiles = mutableMapOf<String, ByteArray>()
    val listingFailures = mutableSetOf<String>()
    val migrationListings = mutableListOf<String>()

    private val _status = MutableStateFlow(SandboxStatus(installed = installed, ready = installed))
    override val status: StateFlow<SandboxStatus> = _status
    override val sessions: StateFlow<List<String>> = MutableStateFlow(emptyList())

    fun updateStatus(status: SandboxStatus) {
        _status.value = status
    }

    override fun setup() {}
    override fun cancel() {}
    override fun reset() {}
    override fun installPackages() {}
    override fun selectDistro(distro: LinuxDistro) {
        _status.value = _status.value.copy(distro = distro)
    }

    override suspend fun executeCommand(command: String, sessionId: String): String = ""
    override suspend fun executeCommandStreaming(
        command: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
        sessionId: String,
    ): CommandHandle = NoOpCommandHandle

    override suspend fun listDirectoryForMigration(path: String, allowMissing: Boolean): List<SandboxFileEntry> {
        migrationListings.add(path)
        check(path !in listingFailures) { "Directory listing failed: $path" }
        return listDirectory(path)
    }

    override suspend fun listDirectory(path: String): List<SandboxFileEntry> {
        val prefix = if (path.endsWith("/")) path else "$path/"
        val children = linkedMapOf<String, Boolean>() // name -> isDirectory
        for (p in files.keys + binaryFiles.keys) {
            if (!p.startsWith(prefix)) continue
            val rest = p.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                children[rest] = false // direct file
            } else {
                val dir = rest.substring(0, slash)
                if (children[dir] != false) children[dir] = true
            }
        }
        return children.map { (name, isDir) ->
            SandboxFileEntry(name = name, path = "$prefix$name", isDirectory = isDir, sizeBytes = 0, lastModifiedMs = 0)
        }
    }

    override suspend fun readTextFile(path: String, maxBytes: Int, force: Boolean): TextFileResult = files[path]?.let { TextFileResult.Text(it) } ?: TextFileResult.Unreadable

    override suspend fun readFileBytes(path: String, maxBytes: Int, rootPath: String): ByteArray? = (binaryFiles[path] ?: files[path]?.encodeToByteArray())?.takeIf { it.size <= maxBytes }

    override suspend fun writeTextFile(path: String, content: String): Boolean {
        files[path] = content
        return true
    }

    override suspend fun openFile(path: String): Result<Unit> = Result.success(Unit)

    override suspend fun deleteEntry(path: String, recursive: Boolean): Boolean {
        val prefix = "$path/"
        val toRemove = (files.keys + binaryFiles.keys).filter { it == path || (recursive && it.startsWith(prefix)) }
        toRemove.forEach {
            files.remove(it)
            binaryFiles.remove(it)
        }
        return toRemove.isNotEmpty()
    }

    override suspend fun renameEntry(path: String, newName: String): Result<String> = Result.success(path)

    override suspend fun importFile(directoryPath: String, source: PlatformFile): Result<String> = Result.failure(UnsupportedOperationException("Not needed by tests"))
}
