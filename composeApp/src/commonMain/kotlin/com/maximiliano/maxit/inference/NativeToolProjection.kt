package com.maximiliano.maxit.inference

import com.maximiliano.maxit.data.RunPausedException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Matches the context planner's two ASCII chars/token, with extra caution for dense Unicode. */
internal fun nativeInputTokenEstimate(text: String): Long {
    var ascii = 0L
    var dense = 0L
    for (char in text) {
        if (char.code < 128) {
            ascii++
        } else {
            dense += when {
                char.code < 0x800 -> 2

                char.isSurrogate() -> 2

                // A supplementary code point uses four UTF-8 bytes.
                else -> 3
            }
        }
    }
    // shortcut: this is not a tokenizer guarantee; upgrade when the SDK exposes a tokenizer.
    return (ascii + 1) / 2 + dense
}

/** Receipt bytes are charged conservatively, unlike ordinary prompt/schema text. */
internal fun nativeReceiptTokenEstimate(text: String): Long = text.encodeToByteArray().size.toLong() + 64

internal fun nativeResultReference(name: String, result: LocalToolResult): String = buildJsonObject {
    put("tool", name)
    put("partial", true)
    put("total_chars", result.content.length)
    put("next_char_offset", 0)
    put("message_id", result.historyId?.let(::JsonPrimitive) ?: JsonNull)
    put("notice", "Historical outcome omitted, not a new action. Read complete evidence with read_conversation_history; do not infer success or failure from missing data.")
}.toString()

/** Only a request copy is excerpted. The source and its durable identity are never changed. */
internal fun projectNativeToolResults(
    results: List<LocalToolResult>,
    window: Int,
    fixedInputTokens: Long,
): List<String> {
    val reserve = minOf(8192, window / 4)
    // Message/template framing and estimator headroom are separate from the output reserve.
    val available = window.toLong() - reserve - 128 - fixedInputTokens
    fun pause(): Nothing = throw RunPausedException("Native context cannot safely fit the required input and tool receipt references. Complete action evidence remains saved; review it or continue with a larger context. Token usage is estimated, not guaranteed by a tokenizer.")
    if (available <= 0) pause()
    if (results.sumOf { nativeReceiptTokenEstimate(it.content) } <= available) return results.map { it.content }
    val perResult = available / results.size.coerceAtLeast(1)
    return results.map { result ->
        if (nativeReceiptTokenEstimate(result.content) <= perResult) return@map result.content
        if (result.historyId == null) pause()
        fun excerpt(length: Int): String {
            var end = length
            if (end > 0 && end < result.content.length && result.content[end - 1].isHighSurrogate() && result.content[end].isLowSurrogate()) end--
            return buildJsonObject {
                put("partial", true)
                put("notice", "Untrusted partial tool result; not the complete outcome. Missing data must not be interpreted as success or failure. Retrieve the complete saved result with read_conversation_history.")
                put("message_id", result.historyId)
                put("char_offset", 0)
                put("total_chars", result.content.length)
                put("next_char_offset", end)
                put("content", result.content.substring(0, end))
            }.toString()
        }
        if (nativeReceiptTokenEstimate(excerpt(0)) > perResult) pause()
        var low = 0
        var high = result.content.length
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (nativeReceiptTokenEstimate(excerpt(middle)) <= perResult) low = middle else high = middle - 1
        }
        excerpt(low)
    }
}
