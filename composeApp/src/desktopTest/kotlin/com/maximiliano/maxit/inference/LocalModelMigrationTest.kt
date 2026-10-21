package com.maximiliano.maxit.inference

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelMigrationTest {
    private val root = Files.createTempDirectory("local-model-migration").toFile()
    private val model = RETIRED_LOCAL_MODELS.single()
    private val source get() = File(root, "${model.id}/${model.fileName}")
    private val destination get() = File(root, "$IMPORTS_DIR/${model.id}/${model.fileName}")
    private val bytes = ByteArray(MIN_MODEL_FILE_BYTES.toInt()) { (it % 251).toByte() }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun installLegacy(marker: String? = null) {
        source.parentFile.mkdirs()
        source.writeBytes(bytes)
        if (marker != null) File(source.parentFile, digestMarkerFileName(source.name)).writeText(marker)
    }

    @Test
    fun migratesBytesAndUserSuppliedMarkerWithoutChangingSelectionOrContext() {
        installLegacy(USER_SUPPLIED_MARKER)
        val savedSelection = "lfm2.5-1.2b-instruct"
        val savedContext = mapOf(savedSelection to 2_048)

        val discovered = discoverRetiredLocalModels(root).single { it.model.id == savedSelection }

        assertEquals(destination.absolutePath, discovered.path)
        assertFalse(source.exists())
        assertContentEquals(bytes, destination.readBytes())
        assertEquals(USER_SUPPLIED_MARKER, File(destination.parentFile, digestMarkerFileName(destination.name)).readText())
        assertEquals(2_048, savedContext[discovered.model.id])
        assertEquals(4_096, discovered.model.defaultContextTokens)
        assertEquals(4_096, discovered.model.maxContextTokens)
        assertEquals(20_000, discovered.model.kvPerTokenBytes)
        assertEquals(bytes.size.toLong(), discovered.model.sizeBytes)
        assertTrue(discovered.model.isImported)
    }

    @Test
    fun repeatDiscoveryIsIdempotentAndPreservesDigestMarker() {
        installLegacy("previously-verified-digest")
        val first = discoverRetiredLocalModels(root)
        assertEquals(first, discoverRetiredLocalModels(root))
        assertContentEquals(bytes, destination.readBytes())
        assertEquals("previously-verified-digest", File(destination.parentFile, digestMarkerFileName(destination.name)).readText())
        assertEquals(1, root.walkTopDown().count { it.isFile && isLitertlmExtension(it.name) })
    }

    @Test
    fun ordinaryImportWithSameFilenameIsNotOverwritten() {
        installLegacy()
        val existingImport = File(root, "$IMPORTS_DIR/${model.fileName}")
        existingImport.parentFile.mkdirs()
        existingImport.writeText("existing imported bytes")

        assertEquals(destination.absolutePath, discoverRetiredLocalModels(root).single().path)
        assertEquals("existing imported bytes", existingImport.readText())
        assertContentEquals(bytes, destination.readBytes())
    }

    @Test
    fun occupiedMigrationDirectoryKeepsOriginalDiscoverableAndRetriesSafely() {
        installLegacy(USER_SUPPLIED_MARKER)
        destination.parentFile.mkdirs()
        destination.writeText("do not overwrite")

        repeat(2) {
            val discovered = discoverRetiredLocalModels(root).single()
            assertEquals(source.absolutePath, discovered.path)
            assertEquals(model.id, discovered.model.id)
            assertContentEquals(bytes, source.readBytes())
            assertEquals("do not overwrite", destination.readText())
            assertEquals(USER_SUPPLIED_MARKER, File(source.parentFile, digestMarkerFileName(source.name)).readText())
        }

        destination.delete()
        destination.parentFile.delete()
        assertEquals(destination.absolutePath, discoverRetiredLocalModels(root).single().path)
        assertContentEquals(bytes, destination.readBytes())
    }

    @Test
    fun failedMoveKeepsSourceAndMarkerUsable() {
        installLegacy(USER_SUPPLIED_MARKER)
        val discovered = discoverRetiredLocalModels(
            modelsDir = root.absolutePath,
            fileSize = { path -> File(path).takeIf { it.isFile }?.length() },
            moveDirectory = { _, _ -> error("Move failed") },
        ).single()

        assertEquals(source.absolutePath, discovered.path)
        assertContentEquals(bytes, source.readBytes())
        assertFalse(destination.exists())
        assertEquals(USER_SUPPLIED_MARKER, File(source.parentFile, digestMarkerFileName(source.name)).readText())
    }

    @Test
    fun missingOrPartialLegacyDownloadDoesNotCreateAnOffer() {
        source.parentFile.mkdirs()
        File(source.parentFile, "${source.name}.tmp").writeText("incomplete")
        assertTrue(discoverRetiredLocalModels(root).isEmpty())
        assertFalse(File(root, IMPORTS_DIR).exists())
        assertNull(findCatalogModelById(model.id))
        assertNull(findCatalogModelByFileName(model.fileName))
        assertTrue(model.downloadUrl.isEmpty())
        assertFalse(resolveImportTarget(model.fileName)!!.matchedCatalog)
    }
}
