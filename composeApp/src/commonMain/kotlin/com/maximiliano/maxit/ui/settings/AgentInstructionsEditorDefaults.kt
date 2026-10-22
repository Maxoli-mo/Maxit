package com.maximiliano.maxit.ui.settings

import com.maximiliano.maxit.data.loadAgentInstructions
import kotlinx.coroutines.CancellationException

internal suspend fun loadAgentInstructionsEditorDefault(
    readDefault: suspend () -> String = { loadAgentInstructions() },
): Result<String> = try {
    Result.success(readDefault())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

internal fun selectAgentInstructionsEditorText(
    savedInstructions: String,
    defaultInstructions: Result<String>?,
): String? = savedInstructions.takeIf { it.isNotEmpty() } ?: defaultInstructions?.getOrNull()
