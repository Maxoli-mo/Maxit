package com.maximiliano.maxit.testutil

import com.maximiliano.maxit.skills.SkillStorage
import com.maximiliano.maxit.skills.validateSkillMigrationKey

class FakeSkillStorage(override val isSupported: Boolean = true) : SkillStorage {
    val bundles = mutableMapOf<String, Map<String, ByteArray>>()
    val fileReads = mutableListOf<Pair<String, String>>()
    private val migrationMarkers = mutableSetOf<String>()
    var migrated: Boolean
        get() = migrationMarkers.any { it == "complete" || it.startsWith("source-") }
        set(value) {
            if (value) migrationMarkers.add("complete") else migrationMarkers.clear()
        }
    override suspend fun readAll(): Map<String, List<String>> = bundles.mapValues { (_, files) -> files.keys.toList() }
    override suspend fun readFile(id: String, path: String, maxBytes: Int): ByteArray {
        fileReads.add(id to path)
        val bytes = bundles.getValue(id).getValue(path)
        require(bytes.size <= maxBytes) { "Skill data exceeds its size limit." }
        return bytes
    }
    override suspend fun install(id: String, files: Map<String, ByteArray>) {
        require(id !in bundles) { "Skill is already installed: $id" }
        bundles[id] = files.toMap()
    }
    override suspend fun remove(id: String) {
        bundles.remove(id)
    }
    override suspend fun isLegacyMigrated(key: String): Boolean {
        validateSkillMigrationKey(key)
        return key in migrationMarkers
    }
    override suspend fun markLegacyMigrated(key: String) {
        validateSkillMigrationKey(key)
        migrationMarkers.add(key)
    }
}
