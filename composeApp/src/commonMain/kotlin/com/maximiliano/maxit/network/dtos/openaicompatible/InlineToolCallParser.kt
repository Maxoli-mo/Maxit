// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.network.dtos.openaicompatible

import com.maximiliano.maxit.network.tools.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ParsedInlineToolCall(
    val name: String,
    val arguments: String,
)

internal data class InlineToolCallExtraction(
    val cleanedText: String,
    val calls: List<ParsedInlineToolCall>,
)

/** A suspicious whole-message envelope is a reason to ask for correction, never to execute text. */
internal fun hasBareToolCallEnvelope(content: String, declaredToolNames: Set<String>): Boolean {
    if (declaredToolNames.isEmpty() || !content.trimStart().startsWith("{")) return false
    val obj = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return false
    if (obj.keys != setOf("tool_calls")) return false
    val calls = obj["tool_calls"] as? JsonArray ?: return false
    return calls.isNotEmpty() && calls.all { element ->
        val call = element as? JsonObject ?: return@all false
        val function = if ("function" in call) {
            if (call.keys.any { it !in setOf("id", "type", "function") }) return@all false
            call["function"] as? JsonObject ?: return@all false
        } else {
            if (call.keys.any { it !in setOf("id", "type", "name", "arguments") }) return@all false
            call
        }
        val name = function["name"] as? JsonPrimitive
        val args = function["arguments"]
        name?.isString == true && name.content in declaredToolNames &&
            (args is JsonObject || (args is JsonPrimitive && args.isString))
    }
}

private const val OPEN_TAG = "<tool_call>"
private const val CLOSE_TAG = "</tool_call>"

private val functionTagRegex = Regex("<function=([\\w.\\-]+)>([\\s\\S]*?)</function>")
private val parameterTagRegex = Regex("<parameter=([\\w.\\-]+)>([\\s\\S]*?)</parameter>")

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Some OpenAI-compatible models (Qwen, Hermes-style fine-tunes, and a number of
 * self-hosted endpoints) occasionally emit tool calls as inline `<tool_call>` XML
 * inside the assistant content instead of populating the structured `tool_calls`
 * field. Accept only whole messages of complete wrappers for declared tools;
 * mixed prose, quoted examples, and incomplete batches remain unchanged.
 *
 * Two block flavors are accepted:
 *  - Hermes / OpenHands XML: `<function=NAME><parameter=KEY>VALUE</parameter>…</function>`
 *  - JSON: `{ "name": "...", "arguments": { … } }`
 *
 * Parameter values are coerced to JSON primitive types using the tool's schema so
 * `timeout=180` becomes a number rather than the string "180".
 */
internal fun extractInlineToolCalls(
    content: String,
    tools: List<Tool>,
): InlineToolCallExtraction {
    val unchanged = InlineToolCallExtraction(content, emptyList())
    val calls = mutableListOf<ParsedInlineToolCall>()
    var pos = 0
    while (pos < content.length) {
        if (content[pos].isWhitespace()) {
            pos++
            continue
        }
        if (!content.startsWith(OPEN_TAG, pos)) return unchanged
        val closeIdx = content.indexOf(CLOSE_TAG, pos + OPEN_TAG.length)
        if (closeIdx < 0) return unchanged
        val inner = content.substring(pos + OPEN_TAG.length, closeIdx).trim()
        if (inner.contains(OPEN_TAG)) return unchanged
        val parsed = parseToolCallBlock(inner, tools) ?: return unchanged
        if (tools.none { it.schema.name == parsed.name }) return unchanged
        calls.add(parsed)
        pos = closeIdx + CLOSE_TAG.length
    }
    return if (calls.isEmpty()) unchanged else InlineToolCallExtraction("", calls)
}

private fun parseToolCallBlock(inner: String, tools: List<Tool>): ParsedInlineToolCall? {
    if (inner.isEmpty()) return null
    return when {
        inner.startsWith("{") -> parseJsonFlavor(inner)
        inner.startsWith("<function=") -> parseXmlFlavor(inner, tools)
        else -> null
    }
}

private fun parseJsonFlavor(inner: String): ParsedInlineToolCall? = try {
    val obj = lenientJson.parseToJsonElement(inner).jsonObject
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return null
    val argsElement = obj["arguments"] ?: obj["parameters"]
    val argsJson = when {
        argsElement == null -> "{}"
        argsElement is JsonObject -> argsElement.toString()
        else -> argsElement.toString()
    }
    ParsedInlineToolCall(name = name, arguments = argsJson)
} catch (_: Throwable) {
    null
}

private fun parseXmlFlavor(inner: String, tools: List<Tool>): ParsedInlineToolCall? {
    val funcMatch = functionTagRegex.matchEntire(inner) ?: return null
    val name = funcMatch.groupValues[1]
    val body = funcMatch.groupValues[2]
    val schema = tools.firstOrNull { it.schema.name == name }?.schema

    val json = buildJsonObject {
        for (match in parameterTagRegex.findAll(body)) {
            val key = match.groupValues[1]
            val raw = match.groupValues[2].trim()
            val type = schema?.parameters?.get(key)?.type
            put(key, coerceParameterValue(raw, type))
        }
    }
    return ParsedInlineToolCall(name = name, arguments = json.toString())
}

private fun coerceParameterValue(
    raw: String,
    declaredType: String?,
): kotlinx.serialization.json.JsonElement = when (declaredType) {
    "integer" -> raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)

    "number" -> raw.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)

    "boolean" -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)

    "array", "object" -> parseJsonOrNull(raw) ?: JsonPrimitive(raw)

    // No schema hint — keep as string. Numeric-looking values stay strings to match
    // the model's literal output unless the schema explicitly asked for a number.
    else -> JsonPrimitive(raw)
}

private fun parseJsonOrNull(raw: String): kotlinx.serialization.json.JsonElement? = try {
    lenientJson.parseToJsonElement(raw)
} catch (_: Throwable) {
    null
}
