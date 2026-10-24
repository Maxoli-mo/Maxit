// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.network.dtos.anthropic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnthropicChatResponseDto(
    val content: List<ContentBlock> = emptyList(),
    val stop_reason: String? = null,
    val rawContent: kotlinx.serialization.json.JsonArray? = null,
    val usage: JsonObject? = null,
) {
    @Serializable
    data class ContentBlock(
        val type: String,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
        val thinking: String? = null,
        val signature: String? = null,
    )
}

fun AnthropicChatResponseDto.extractText(): String = content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
