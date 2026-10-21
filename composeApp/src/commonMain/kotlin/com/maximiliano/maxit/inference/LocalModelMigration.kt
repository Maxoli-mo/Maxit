package com.maximiliano.maxit.inference

/** Compatibility metadata only: these models must never reappear in the download catalog. */
internal val RETIRED_LOCAL_MODELS = listOf(
    LocalModel(
        id = "lfm2.5-1.2b-instruct",
        displayName = "LFM2.5 1.2B Instruct",
        fileName = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
        sizeBytes = 736_220_768L,
        downloadUrl = "",
        gpuMemoryMb = 300,
        defaultContextTokens = 4_096,
        maxContextTokens = 4_096,
        kvPerTokenBytes = 20_000,
        isImported = true,
    ),
)

internal data class RetiredModelFile(val model: LocalModel, val path: String)

/**
 * Move the entire former catalog directory (including digest/user-supplied markers) into
 * imports, retaining its id so saved selections and per-model context settings still apply.
 * Nested directories avoid collisions with ordinary flat imports of the same filename.
 * [moveDirectory] must refuse an existing destination and leave the source intact on failure.
 * An occupied destination or failed move leaves the original model usable and retries later.
 */
internal fun discoverRetiredLocalModels(
    modelsDir: String,
    fileSize: (String) -> Long?,
    moveDirectory: (source: String, destination: String) -> Unit,
): List<RetiredModelFile> = RETIRED_LOCAL_MODELS.mapNotNull { model ->
    val oldDir = "$modelsDir/${model.id}"
    val importedDir = "$modelsDir/$IMPORTS_DIR/${model.id}"
    val oldPath = "$oldDir/${model.fileName}"
    val importedPath = "$importedDir/${model.fileName}"
    if (fileSize(oldPath) != null) {
        runCatching { moveDirectory(oldDir, importedDir) }
    }
    // Prefer the original on collision; never silently switch the saved selection to
    // different bytes that happen to occupy the destination.
    val path = if (fileSize(oldPath) != null) oldPath else importedPath
    val size = fileSize(path) ?: return@mapNotNull null
    RetiredModelFile(model.copy(sizeBytes = size), path)
}
