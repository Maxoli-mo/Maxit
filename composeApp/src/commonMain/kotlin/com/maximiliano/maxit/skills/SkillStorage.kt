package com.maximiliano.maxit.skills

import io.github.vinceglb.filekit.PlatformFile

/** App-owned skill bundles, independent of the optional Linux sandbox. */
interface SkillStorage {
    val isSupported: Boolean get() = true
    fun directoryPath(id: String): String? = null

    /** Lists validated bundle paths without loading their contents. Empty lists identify damaged bundles. */
    suspend fun readAll(): Map<String, List<String>>
    suspend fun readFile(id: String, path: String, maxBytes: Int): ByteArray
    suspend fun install(id: String, files: Map<String, ByteArray>)
    suspend fun remove(id: String)
    suspend fun isLegacyMigrated(key: String = "complete"): Boolean
    suspend fun markLegacyMigrated(key: String = "complete")
}

internal fun validateSkillMigrationKey(key: String) {
    require(key.length in 1..128 && Regex("[a-z0-9-]+").matches(key)) { "Invalid skill migration key." }
}

expect fun createSkillStorage(): SkillStorage

/** Reads a local .md or .zip with enforced streaming limits before parsing. */
internal expect suspend fun readSkillImport(file: PlatformFile): DownloadedSkill
