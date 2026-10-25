package com.maximiliano.maxit.network

import com.maximiliano.maxit.data.ConversationWriteException
import com.maximiliano.maxit.network.dtos.anthropic.AnthropicChatResponseDto
import com.maximiliano.maxit.network.dtos.gemini.GeminiChatResponseDto
import com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleChatResponseDto
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Never automatically retry a generation once a stream has begun. */
class StreamInterruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal val streamJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
private const val MAX_EVENT_CHARS = 1_048_576
private const val MAX_RESPONSE_CHARS = 8_388_608

/** One bounded SSE event at a time; handles CRLF, comments, multiline data, and final EOF. */
internal suspend fun readSse(channel: ByteReadChannel, isDone: () -> Boolean = { false }, event: suspend (String?, String) -> Unit) {
    var type: String? = null
    var firstLine = true
    val data = StringBuilder()
    suspend fun flush() {
        if (data.isNotEmpty()) event(type, data.toString().removeSuffix("\n"))
        data.clear()
        type = null
    }
    while (true) {
        val rawLine = channel.readUTF8Line(MAX_EVENT_CHARS) ?: break
        val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
        firstLine = false
        when {
            line.isEmpty() -> {
                flush()
                if (isDone()) return
            }

            line.startsWith("event:") -> type = line.substringAfter(':').removePrefix(" ")

            line == "data" || line.startsWith("data:") -> {
                data.append(if (line == "data") "" else line.substringAfter(':').removePrefix(" ")).append('\n')
                if (data.length > MAX_EVENT_CHARS) throw StreamInterruptedException("The provider returned an oversized stream event.")
            }
        }
    }
    flush()
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private class TextBuffer(private val onText: suspend (String) -> Unit) {
    val text = StringBuilder()
    suspend fun append(delta: String) {
        if (text.length + delta.length > MAX_RESPONSE_CHARS) throw StreamInterruptedException("The provider response exceeded the per-response safety size.")
        text.append(delta)
        if (delta.isNotEmpty()) onText(delta)
    }
}

/** Some live endpoints omit Content-Type even for SSE; sniff without consuming the decoder's input. */
internal suspend fun hasSsePrefix(channel: ByteReadChannel): Boolean {
    val markers = listOf("event:", "data:", ":", "id:", "retry:")
    for (size in 3..64) {
        val prefix = channel.peek(size)?.toByteArray()?.decodeToString()?.trimStart('\uFEFF', ' ', '\r', '\n', '\t') ?: return false
        if (prefix.isEmpty()) continue
        if (markers.any { prefix.startsWith(it) }) return true
        if (markers.none { it.startsWith(prefix) }) return false
    }
    return false
}

internal suspend fun readModelBody(channel: ByteReadChannel): String {
    val bytes = channel.readRemaining(MAX_RESPONSE_CHARS.toLong() + 1).readByteArray()
    if (bytes.size > MAX_RESPONSE_CHARS) throw StreamInterruptedException("The provider response exceeded the per-response safety size.")
    return bytes.decodeToString()
}

internal suspend fun readChatCompletionBody(raw: String, onText: suspend (String) -> Unit): OpenAICompatibleChatResponseDto {
    val response = streamJson.decodeFromString<OpenAICompatibleChatResponseDto>(raw)
    response.choices.firstOrNull()?.message?.effectiveContent?.let { onText(it) }
    val finish = response.choices.firstOrNull()?.finishReason
    if (finish != null && finish !in setOf("stop", "tool_calls", "function_call")) throw StreamInterruptedException("The provider did not complete the response. Received text was preserved.")
    validateChatCompletionTools(response.choices.firstOrNull()?.message?.toolCalls.orEmpty())
    return response
}

private fun validateChatCompletionTools(calls: List<OpenAICompatibleChatResponseDto.ToolCall>) {
    if (calls.size > 256) throw StreamInterruptedException("The provider proposed more than 256 parallel actions; no calls were dispatched.")
    if (calls.map { it.id }.distinct().size != calls.size) throw StreamInterruptedException("The provider returned duplicate tool-call identities; no calls were dispatched.")
    for (call in calls) {
        if (call.id.isBlank() || call.type != "function" || call.function.name.isBlank()) throw StreamInterruptedException("The provider returned an incomplete or unsupported tool identity; no calls were dispatched.")
        val arguments = call.function.arguments
        if (arguments.length > 1_048_576 || runCatching { streamJson.parseToJsonElement(arguments) as? JsonObject }.getOrNull() == null) {
            throw StreamInterruptedException("The provider returned invalid or oversized tool arguments; no calls were dispatched.")
        }
    }
}

internal suspend fun readAnthropicBody(raw: String, onText: suspend (String) -> Unit): AnthropicChatResponseDto {
    val response = streamJson.decodeFromString<AnthropicChatResponseDto>(raw).copy(rawContent = streamJson.parseToJsonElement(raw).jsonObject["content"] as? JsonArray)
    onText(response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n"))
    if (response.stop_reason != null && response.stop_reason !in setOf("end_turn", "tool_use", "stop_sequence")) throw StreamInterruptedException("Anthropic did not complete the response. Received text was preserved.")
    return response
}

internal suspend fun readGeminiBody(raw: String, onText: suspend (String) -> Unit): GeminiChatResponseDto {
    val response = streamJson.decodeFromString<GeminiChatResponseDto>(raw)
    onText(response.candidates.firstOrNull()?.content?.parts.orEmpty().filterNot { it.isThought }.mapNotNull { it.text }.joinToString("\n"))
    val finish = response.candidates.firstOrNull()?.finishReason
    if (finish != null && finish != "STOP") throw StreamInterruptedException("Gemini did not complete the response. Received text was preserved.")
    return response
}

internal suspend fun readResponsesBody(raw: String, onText: suspend (String) -> Unit = {}): com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesResponseDto {
    val response = streamJson.decodeFromString<com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesResponseDto>(raw).copy(rawOutput = streamJson.parseToJsonElement(raw).jsonObject["output"] as? JsonArray)
    response.outputText?.let { onText(it) }
    if (response.error != null || (response.status != null && response.status != "completed")) throw StreamInterruptedException("The provider did not complete the response. Received text was preserved.")
    validateResponsesTools(response)
    return response
}

internal suspend fun readChatCompletionStream(channel: ByteReadChannel, onText: suspend (String) -> Unit): OpenAICompatibleChatResponseDto {
    val text = TextBuffer(onText)
    val reasoning = StringBuilder()
    data class Call(var id: String = "", var type: String = "function", val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
    val calls = mutableMapOf<Int, Call>()
    val details = linkedMapOf<String, JsonObject>()
    var detailChars = 0
    var finish: String? = null
    var ended = false
    var usage: JsonObject? = null
    try {
        readSse(channel, { ended }) { _, data ->
            if (data == "[DONE]") {
                ended = true
            } else {
                check(!ended) { "Data after stream completion" }
                val chunk = streamJson.parseToJsonElement(data).jsonObject
                chunk.obj("usage")?.let { usage = it }
                if (chunk["error"] != null) throw StreamInterruptedException("The provider reported a streaming error.")
                val choice = chunk.array("choices").mapNotNull { it as? JsonObject }.firstOrNull { it["index"]?.jsonPrimitive?.intOrNull in listOf(null, 0) }
                if (choice != null) {
                    choice.text("finish_reason")?.let { finish = it }
                    val delta = choice.obj("delta") ?: choice.obj("message")
                    if (delta != null) {
                        val content = delta["content"]
                        val words = when (content) {
                            is JsonPrimitive -> content.contentOrNull.orEmpty()
                            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.text("text") }.joinToString("")
                            else -> ""
                        }
                        text.append(if (words.isBlank()) delta.text("refusal")?.takeIf { it.isNotBlank() } ?: words else words)
                        reasoning.append(delta.text("reasoning_content") ?: delta.text("reasoning") ?: "")
                        delta.array("reasoning_details").forEachIndexed { position, value ->
                            val fragment = value.jsonObject
                            val key = fragment.text("index") ?: fragment.text("id") ?: position.toString()
                            val previous = details[key].orEmpty()
                            val merged = previous.toMutableMap().apply { putAll(fragment) }
                            for (field in listOf("text", "data", "signature", "summary")) fragment.text(field)?.let { merged[field] = JsonPrimitive((previous[field] as? JsonPrimitive)?.contentOrNull.orEmpty() + it) }
                            details[key] = JsonObject(merged)
                            detailChars += value.toString().length
                            if (details.size > 512) throw StreamInterruptedException("The provider returned too many reasoning blocks.")
                        }
                        for (value in delta.array("tool_calls")) {
                            val tc = value.jsonObject
                            val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val call = calls.getOrPut(index) { Call() }
                            if (calls.size > 256) throw StreamInterruptedException("The provider proposed an oversized parallel batch. Split the actions into smaller batches.")
                            tc.text("id")?.takeIf { it.isNotBlank() }?.let { call.id = it }
                            tc.text("type")?.let { call.type = it }
                            tc.obj("function")?.let { function ->
                                call.name.append(function.text("name").orEmpty())
                                call.args.append(function.text("arguments").orEmpty())
                            }
                        }
                        if (text.text.length + detailChars + reasoning.length + calls.values.sumOf { it.args.length + it.name.length } > MAX_RESPONSE_CHARS) throw StreamInterruptedException("The provider response exceeded the per-response safety size.")
                    }
                }
            }
        }
        if (finish !in listOf("stop", "tool_calls", "function_call")) {
            throw StreamInterruptedException(if (finish == "length") "The response reached the model's output limit. The partial answer was preserved." else "The stream ended without a complete answer. The partial answer was preserved.")
        }
        val toolCalls = calls.entries.sortedBy { it.key }.map { (_, call) ->
            OpenAICompatibleChatResponseDto.ToolCall(call.id, type = call.type, function = OpenAICompatibleChatResponseDto.FunctionCall(call.name.toString(), call.args.toString()))
        }
        validateChatCompletionTools(toolCalls)
        return OpenAICompatibleChatResponseDto(listOf(OpenAICompatibleChatResponseDto.Choice(OpenAICompatibleChatResponseDto.Choice.Message(role = "assistant", content = text.text.toString(), reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() }, reasoningDetails = details.values.toList().takeIf { it.isNotEmpty() }?.let(::JsonArray), toolCalls = toolCalls.takeIf { it.isNotEmpty() }), finishReason = finish)), usage = usage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConversationWriteException) {
        throw e
    } catch (e: StreamInterruptedException) {
        throw e
    } catch (e: Exception) {
        throw StreamInterruptedException("The response stream was interrupted. Received text was preserved; tools were not replayed.", e)
    }
}

internal suspend fun readAnthropicStream(channel: ByteReadChannel, onText: suspend (String) -> Unit): AnthropicChatResponseDto {
    val text = TextBuffer(onText)
    val blocks = mutableMapOf<Int, JsonObject>()
    val arguments = mutableMapOf<Int, StringBuilder>()
    var usage = JsonObject(emptyMap())
    var finish: String? = null
    var ended = false
    try {
        readSse(channel, { ended }) { event, data ->
            val chunk = streamJson.parseToJsonElement(data).jsonObject
            val index = chunk["index"]?.jsonPrimitive?.intOrNull ?: 0
            (chunk.obj("usage") ?: chunk.obj("message")?.obj("usage"))?.let { usage = JsonObject(usage + it) }
            when (chunk.text("type") ?: event) {
                "error" -> throw StreamInterruptedException("Anthropic reported a streaming error.")

                "content_block_start" -> {
                    val block = chunk.obj("content_block") ?: error("Missing content block")
                    blocks[index] = block
                    if (blocks.size > 512) throw StreamInterruptedException("Anthropic returned too many content blocks in one response.")
                    if (block.text("type") == "text") text.append(block.text("text").orEmpty())
                }

                "content_block_delta" -> {
                    val delta = chunk.obj("delta") ?: error("Missing content delta")
                    when (delta.text("type")) {
                        "text_delta" -> {
                            val words = delta.text("text").orEmpty()
                            text.append(words)
                            val previous = blocks[index] ?: error("Delta before block")
                            blocks[index] = JsonObject(previous + ("text" to JsonPrimitive(previous.text("text").orEmpty() + words)))
                        }

                        "input_json_delta" -> {
                            check(blocks[index]?.text("type") == "tool_use") { "Tool delta before its content block" }
                            arguments.getOrPut(index) { StringBuilder() }.append(delta.text("partial_json").orEmpty())
                        }

                        "thinking_delta" -> {
                            val previous = blocks[index] ?: error("Delta before block")
                            blocks[index] = JsonObject(previous + ("thinking" to JsonPrimitive(previous.text("thinking").orEmpty() + delta.text("thinking").orEmpty())))
                        }

                        "signature_delta" -> {
                            val previous = blocks[index] ?: error("Delta before block")
                            blocks[index] = JsonObject(previous + ("signature" to JsonPrimitive(previous.text("signature").orEmpty() + delta.text("signature").orEmpty())))
                        }
                    }
                }

                "content_block_stop" -> {
                    arguments.remove(index)?.let { json ->
                        val previous = blocks[index] ?: error("Missing tool block")
                        blocks[index] = JsonObject(previous + ("input" to streamJson.parseToJsonElement(json.toString()).jsonObject))
                    }
                }

                "message_delta" -> finish = chunk.obj("delta")?.text("stop_reason")

                "message_stop" -> ended = true
            }
            if (blocks.values.sumOf { it.toString().length } + arguments.values.sumOf { it.length } > MAX_RESPONSE_CHARS) throw StreamInterruptedException("Anthropic response exceeded the per-response safety size.")
        }
        if (!ended || arguments.isNotEmpty() || finish !in listOf("end_turn", "tool_use", "stop_sequence")) throw StreamInterruptedException("Anthropic did not finish the response. The partial answer was preserved.")
        val content = blocks.entries.sortedBy { it.key }.map { streamJson.decodeFromJsonElement(AnthropicChatResponseDto.ContentBlock.serializer(), it.value) }
        return AnthropicChatResponseDto(content, finish, JsonArray(blocks.entries.sortedBy { it.key }.map { it.value }), usage.takeIf { it.isNotEmpty() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConversationWriteException) {
        throw e
    } catch (e: StreamInterruptedException) {
        throw e
    } catch (e: Exception) {
        throw StreamInterruptedException("The Anthropic stream was interrupted. Received text was preserved.", e)
    }
}

internal suspend fun readGeminiStream(channel: ByteReadChannel, onText: suspend (String) -> Unit): GeminiChatResponseDto {
    val text = TextBuffer(onText)
    val parts = mutableListOf<GeminiChatResponseDto.Part>()
    val partText = StringBuilder()
    fun finishPart() {
        val previous = parts.lastOrNull()
        if (previous?.text != null) parts[parts.lastIndex] = previous.copy(text = partText.toString())
    }
    var fragmentCount = 0
    var hasVisiblePart = false
    var responseChars = 0
    var usage: JsonObject? = null
    var finish: String? = null
    try {
        readSse(channel) { _, data ->
            val chunk = streamJson.parseToJsonElement(data).jsonObject
            chunk.obj("usageMetadata")?.let { usage = it }
            if (chunk["error"] != null) throw StreamInterruptedException("Gemini reported a streaming error.")
            val candidate = chunk.array("candidates").firstOrNull()?.jsonObject
            if (candidate != null) {
                candidate.text("finishReason")?.let { finish = it }
                for ((index, value) in candidate.obj("content")?.array("parts").orEmpty().withIndex()) {
                    val part = streamJson.decodeFromJsonElement(GeminiChatResponseDto.Part.serializer(), value)
                    val previous = parts.lastOrNull()
                    // Only the event boundary can split a logical unsigned text part.
                    val continuesText = index == 0 && part.text != null && previous?.text != null &&
                        part.isThought == previous.isThought && part.functionCall == null && previous.functionCall == null &&
                        part.thoughtSignature == null && previous.thoughtSignature == null
                    if (!part.isThought) {
                        part.text?.let {
                            if (hasVisiblePart && !continuesText) text.append("\n")
                            text.append(it)
                            hasVisiblePart = true
                        }
                    }
                    if (continuesText) {
                        partText.append(part.text)
                    } else if (part.text != null || part.functionCall != null || part.thoughtSignature != null) {
                        finishPart()
                        parts.add(part)
                        partText.clear()
                        part.text?.let { partText.append(it) }
                    }
                    responseChars += part.text.orEmpty().length + (part.functionCall?.toString()?.length ?: 0) + part.thoughtSignature.orEmpty().length
                    fragmentCount++
                    if (fragmentCount > 32_768) throw StreamInterruptedException("Gemini returned too many fragments in one response.")
                }
            }
            if (responseChars > MAX_RESPONSE_CHARS) throw StreamInterruptedException("Gemini response exceeded the per-response safety size.")
        }
        if (finish != "STOP") throw StreamInterruptedException("Gemini did not finish the response. The partial answer was preserved.")
        finishPart()
        return GeminiChatResponseDto(listOf(GeminiChatResponseDto.Candidate(GeminiChatResponseDto.Content(parts), finishReason = finish)), usageMetadata = usage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConversationWriteException) {
        throw e
    } catch (e: StreamInterruptedException) {
        throw e
    } catch (e: Exception) {
        throw StreamInterruptedException("The Gemini stream was interrupted. Received text was preserved.", e)
    }
}
