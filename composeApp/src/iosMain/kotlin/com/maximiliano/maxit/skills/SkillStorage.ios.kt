package com.maximiliano.maxit.skills

import io.github.vinceglb.filekit.PlatformFile

actual fun createSkillStorage(): SkillStorage = object : SkillStorage {
    override val isSupported = false
    override suspend fun readAll(): Map<String, List<String>> = emptyMap()
    override suspend fun readFile(id: String, path: String, maxBytes: Int): ByteArray = unsupportedSkills()
    override suspend fun install(id: String, files: Map<String, ByteArray>): Unit = unsupportedSkills()
    override suspend fun remove(id: String): Unit = unsupportedSkills()
    override suspend fun isLegacyMigrated(key: String): Boolean = false
    override suspend fun markLegacyMigrated(key: String): Unit = unsupportedSkills()
}

internal actual suspend fun readSkillImport(file: PlatformFile): DownloadedSkill = unsupportedSkills()
internal actual fun readSkillZip(bytes: ByteArray): Map<String, ByteArray> = unsupportedSkills()

private fun unsupportedSkills(): Nothing = throw UnsupportedOperationException("Native skills are supported on Android and desktop only.")
