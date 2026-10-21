package com.maximiliano.maxit.inference

import java.io.File
import java.nio.file.Files

internal fun discoverRetiredLocalModels(modelsDir: File): List<RetiredModelFile> = discoverRetiredLocalModels(
    modelsDir = modelsDir.absolutePath,
    fileSize = { path -> File(path).takeIf { it.isFile }?.length() },
    moveDirectory = { source, destination ->
        val target = File(destination)
        target.parentFile.mkdirs()
        // No REPLACE_EXISTING or ATOMIC_MOVE: both can replace an occupied destination.
        // Moving within the storage root preserves bytes and markers without a copy/delete fallback.
        Files.move(File(source).toPath(), target.toPath())
    },
)
