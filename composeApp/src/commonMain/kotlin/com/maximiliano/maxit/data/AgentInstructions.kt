package com.maximiliano.maxit.data

import maxit.composeapp.generated.resources.Res

// Built-in behavior is always loaded; user preferences are composed separately.
internal suspend fun loadAgentInstructions(): String = Res.readBytes("files/prompts/agent-instructions.md").decodeToString().trim()
