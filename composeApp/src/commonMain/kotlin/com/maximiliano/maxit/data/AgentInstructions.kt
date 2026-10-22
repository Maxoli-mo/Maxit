package com.maximiliano.maxit.data

import maxit.composeapp.generated.resources.Res

// Keep saved overrides verbatim; the legacy soul_text storage/import key is unchanged.
internal suspend fun loadAgentInstructions(savedInstructions: String = ""): String = savedInstructions.ifEmpty {
    Res.readBytes("files/prompts/agent-instructions.md").decodeToString().trim()
}
