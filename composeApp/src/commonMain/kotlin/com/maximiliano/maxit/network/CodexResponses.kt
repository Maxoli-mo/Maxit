package com.maximiliano.maxit.network

import com.maximiliano.maxit.data.Service
import com.maximiliano.maxit.httpClient
import com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleModelResponseDto
import com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesRequestDto
import com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Public release whose /models client_version contract is pinned, not Maxit's app version.
internal const val CODEX_CLIENT_VERSION = "0.164.0"

private val codexJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun codexSignInError() = CodexAuthException("Your ChatGPT sign-in has expired. Sign in again to use Codex.")

/** A separate client deliberately installs no Logging plugin, even in debug builds. */
internal class CodexResponses(
    private val credentials: suspend (instanceId: String, forceRefresh: Boolean) -> CodexTokens,
    private val client: HttpClient = httpClient {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    },
    private val currentGeneration: (instanceId: String) -> Long? = { null },
) {
    private val modelsGuard = Mutex()
    private val modelsWithoutSummary = mutableMapOf<String, Set<String>>()

    suspend fun responses(instanceId: String, request: OpenAIResponsesRequestDto, sessionId: String, expectedGeneration: Long? = null, onText: suspend (String) -> Unit = {}): OpenAIResponsesResponseDto {
        val supportsSummary = modelsGuard.withLock { request.model !in modelsWithoutSummary[instanceId].orEmpty() }
        val body = codexRequestBody(request, supportsSummary).toString()
        // shortcut: uncompressed backend requests can reject >1 MiB; add compression if needed.
        if (body.encodeToByteArray().size > 1_048_576) {
            throw OpenAICompatibleBadRequestException("This Codex conversation is too large to send. Start a shorter conversation or remove large attachments.")
        }
        return authenticated(instanceId, expectedGeneration) { tokens ->
            client.preparePost(Service.OpenAICodex.responsesUrl!!) {
                codexHeaders(tokens)
                header("session-id", sessionId)
                accept(ContentType.Text.EventStream)
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                if (response.status.value == 401) {
                    null
                } else {
                    checkCodexStatus(response.status.value, response.status.isSuccess())
                    readCodexResponse(response.bodyAsChannel(), onText)
                }
            }
        }
    }

    suspend fun models(instanceId: String, expectedGeneration: Long? = null): OpenAICompatibleModelResponseDto = authenticated(instanceId, expectedGeneration) { tokens ->
        client.prepareGet(Service.OpenAICodex.modelsUrl!!) {
            codexHeaders(tokens)
            parameter("client_version", CODEX_CLIENT_VERSION)
            accept(ContentType.Application.Json)
        }.execute { response ->
            if (response.status.value == 401) {
                null
            } else {
                checkCodexStatus(response.status.value, response.status.isSuccess())
                val catalog = parseCodexObject(response.bodyAsText())["models"] as? JsonArray
                    ?: throw invalidCodexResponse()
                val unsupported = catalog.mapNotNull { item ->
                    val model = item as? JsonObject ?: return@mapNotNull null
                    model.text("slug")?.takeIf { model.flag("supports_reasoning_summary_parameter") == false }
                }.toSet()
                modelsGuard.withLock { modelsWithoutSummary[instanceId] = unsupported }
                codexModels(catalog)
            }
        }
    }

    private suspend fun <T : Any> authenticated(instanceId: String, expectedGeneration: Long?, request: suspend (CodexTokens) -> T?): T {
        var generation = expectedGeneration ?: currentGeneration(instanceId)
        var accountId: String? = null
        fun changed(): Nothing = throw CodexAuthException("ChatGPT sign-in changed. Sign in again and send a new request.")
        fun checkGeneration() {
            val current = currentGeneration(instanceId)
            if (generation != null && current != null && current != generation) changed()
        }
        repeat(2) { attempt ->
            checkGeneration()
            val tokens = try {
                credentials(instanceId, attempt == 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodexAuthException) {
                throw e
            } catch (_: Exception) {
                throw codexSignInError()
            }
            if (tokens.accessToken.isBlank() || tokens.accountId.isBlank()) throw codexSignInError()
            if (generation == null) generation = tokens.sessionGeneration
            if (tokens.sessionGeneration != generation || (accountId != null && accountId != tokens.accountId)) changed()
            accountId = tokens.accountId
            checkGeneration()
            val result = request(tokens)
            checkGeneration()
            result?.let { return it }
        }
        throw codexSignInError()
    }
}

private fun HttpRequestBuilder.codexHeaders(tokens: CodexTokens) {
    bearerAuth(tokens.accessToken)
    header("ChatGPT-Account-Id", tokens.accountId)
    header("originator", "maxit")
    header("OpenAI-Beta", "responses=experimental")
}

internal fun codexRequestBody(request: OpenAIResponsesRequestDto, supportsSummary: Boolean = true): JsonObject {
    val (instructions, input) = request.input.partition { it.text("role") in setOf("system", "developer") }
    val instructionText = instructions.joinToString("\n\n") { item ->
        when (val content = item["content"]) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.text("text") }.joinToString("\n")
            else -> throw OpenAICompatibleBadRequestException("Codex instructions must contain text.")
        }
    }
    return buildJsonObject {
        codexJson.encodeToJsonElement(OpenAIResponsesRequestDto.serializer(), request.copy(input = input)).jsonObject.forEach { (key, value) -> put(key, value) }
        put("store", false)
        put("include", JsonArray(request.include.map { JsonPrimitive(it) }))
        put("stream", true)
        // Legacy device-token backend compatibility; newer Codex clients use input items instead.
        put("instructions", instructionText)
        if (supportsSummary) put("reasoning", buildJsonObject { put("summary", "auto") })
    }
}

private fun codexModels(catalog: JsonArray): OpenAICompatibleModelResponseDto = OpenAICompatibleModelResponseDto(
    data = catalog.mapNotNull { item ->
        val model = item as? JsonObject ?: return@mapNotNull null
        val slug = model.text("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (model.text("visibility") != "list" || model.flag("visible") == false) return@mapNotNull null
        OpenAICompatibleModelResponseDto.Model(
            id = slug,
            name = model.text("display_name"),
            description = model.text("description"),
            owned_by = "openai",
            context_window = (model["context_window"] as? JsonPrimitive)?.longOrNull,
        )
    },
)

/** Deltas are delivered immediately; only a terminal completed event permits tool dispatch. */
internal suspend fun readCodexResponse(channel: ByteReadChannel, onText: suspend (String) -> Unit = {}): OpenAIResponsesResponseDto {
    var completed: OpenAIResponsesResponseDto? = null
    val items = mutableMapOf<Int, JsonObject>()
    val pending = mutableSetOf<Int>()
    var unindexedOutput = false
    var itemChars = 0
    var receivedText = false
    var textChars = 0
    try {
        readSse(channel, { completed != null }) { event, data ->
            if (completed == null) {
                if (data != "[DONE]") {
                    val payload = parseCodexObject(data)
                    val type = payload.text("type") ?: event.orEmpty()
                    if ("output_index" in payload || type in setOf("response.output_item.added", "response.output_item.done")) {
                        val index = (payload["output_index"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..511 }?.toInt()
                            ?: throw StreamInterruptedException("The provider returned an invalid output index. No calls were dispatched.")
                        if (type != "response.output_item.done") {
                            pending.add(index)
                        } else {
                            val item = payload["item"] as? JsonObject ?: throw StreamInterruptedException("The provider returned an invalid completed output item.")
                            val previous = items[index]
                            if (previous != null && previous != item) throw StreamInterruptedException("The provider returned conflicting completed output items.")
                            if (previous == null) itemChars += item.toString().length
                            if (itemChars > 8_388_608) throw StreamInterruptedException("The provider output exceeded the per-response safety size.")
                            items[index] = item
                            pending.remove(index)
                        }
                    } else if (type.startsWith("response.output_") || type.startsWith("response.function_call_arguments.") || type.startsWith("response.content_part.") || "item_id" in payload) {
                        unindexedOutput = true
                    }
                    if (type == "response.output_text.delta") {
                        val delta = payload.text("delta").orEmpty()
                        textChars += delta.length
                        if (textChars > 8_388_608) throw StreamInterruptedException("The response exceeded the per-response safety size.")
                        receivedText = true
                        onText(delta)
                    }
                }
                val terminal = decodeCodexEvent(event, data)
                if (terminal != null) {
                    completed = if (terminal.output.isEmpty() && (items.isNotEmpty() || pending.isNotEmpty() || receivedText || unindexedOutput)) {
                        if (unindexedOutput || pending.isNotEmpty() || items.isEmpty() || items.keys != (0 until items.size).toSet()) throw StreamInterruptedException("The provider completed without all output items. Received text was preserved; no calls were dispatched.")
                        // Some streams omit the terminal output list; only done items are reusable.
                        val raw = JsonArray(items.entries.sortedBy { it.key }.map { it.value })
                        val output = try {
                            raw.map { codexJson.decodeFromJsonElement(OpenAIResponsesResponseDto.OutputItem.serializer(), it) }
                        } catch (_: IllegalArgumentException) {
                            throw StreamInterruptedException("The provider returned invalid completed output items. No calls were dispatched.")
                        }
                        if (textChars > 0 && output.none { item -> item.type == "message" && item.content.orEmpty().any { !it.text.isNullOrEmpty() } }) throw StreamInterruptedException("The provider completed without the received answer text. Received text was preserved; no calls were dispatched.")
                        terminal.copy(output = output, rawOutput = raw)
                    } else {
                        terminal
                    }
                }
            }
        }
        return (completed ?: throw StreamInterruptedException("The response disconnected before completion. Received text was preserved.")).also(::validateResponsesTools)
    } catch (e: CancellationException) {
        throw e
    } catch (e: com.maximiliano.maxit.data.ConversationWriteException) {
        throw e
    } catch (e: Exception) {
        if (receivedText && e !is StreamInterruptedException) throw StreamInterruptedException("The response stream was interrupted. Received text was preserved.", e)
        throw e
    }
}

internal fun validateResponsesTools(response: OpenAIResponsesResponseDto) {
    val calls = response.output.filter { it.type == "function_call" }
    if (calls.size > 256 || calls.map { it.callId }.distinct().size != calls.size) throw StreamInterruptedException("The provider returned oversized or duplicate tool calls; no calls were dispatched.")
    for (call in calls) {
        val args = call.arguments
        if (call.callId.isNullOrBlank() || call.name.isNullOrBlank() || args == null || args.length > 1_048_576 || runCatching { codexJson.parseToJsonElement(args) as? JsonObject }.getOrNull() == null) {
            throw StreamInterruptedException("The provider returned an incomplete tool call or invalid arguments; no calls were dispatched.")
        }
    }
}

private fun decodeCodexEvent(event: String?, data: String): OpenAIResponsesResponseDto? {
    if (data == "[DONE]") throw StreamInterruptedException("The response ended before completion. Received text was preserved; no calls from this response were dispatched.")
    val payload = parseCodexObject(data)
    when (payload.text("type") ?: event) {
        "response.completed" -> {
            val response = payload["response"] as? JsonObject ?: throw invalidCodexResponse()
            val dto = try {
                codexJson.decodeFromJsonElement(OpenAIResponsesResponseDto.serializer(), response)
            } catch (_: IllegalArgumentException) {
                throw invalidCodexResponse()
            }
            if (dto.error != null || dto.status == "failed") throw codexTerminalError(payload)
            if (dto.status != null && dto.status != "completed") throw codexTerminalError(payload, incompleteCodexResponse())
            return dto.copy(rawOutput = response["output"] as? JsonArray)
        }

        "response.failed", "error", "response.error" -> throw codexTerminalError(payload)

        "response.incomplete", "response.cancelled" -> throw codexTerminalError(payload, incompleteCodexResponse())

        else -> return null
    }
}

private fun codexTerminalError(payload: JsonObject, fallback: Exception = failedCodexResponse()): Exception {
    val error = ((payload["response"] as? JsonObject)?.get("error") as? JsonObject)
        ?: (payload["error"] as? JsonObject)
        ?: payload
    // Classify only known machine codes; never expose backend messages or token/prompt fragments.
    return when ((error.text("code") ?: error.text("type"))?.lowercase()) {
        "usage_limit_reached", "insufficient_quota" -> OpenAICompatibleQuotaExhaustedException()

        "rate_limit_exceeded" -> OpenAICompatibleRateLimitExceededException()

        "authentication_error", "authentication_required", "auth_error", "unauthorized",
        "token_expired", "expired_token", "access_token_expired", "invalid_token", "invalid_access_token", "invalid_api_key", "token_revoked", "session_expired",
        -> codexSignInError()

        else -> fallback
    }
}

private fun parseCodexObject(text: String): JsonObject = try {
    codexJson.parseToJsonElement(text) as? JsonObject ?: throw invalidCodexResponse()
} catch (_: IllegalArgumentException) {
    throw invalidCodexResponse()
}

private fun invalidCodexResponse() = OpenAICompatibleGenericException("Codex returned an invalid response. Try again.")
private fun failedCodexResponse() = OpenAICompatibleGenericException("Codex could not complete this response. Try again.")
private fun incompleteCodexResponse() = OpenAICompatibleGenericException("Codex returned an incomplete response. Try again.")
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun checkCodexStatus(status: Int, success: Boolean) {
    if (success) return
    // Never echo backend bodies: errors can include prompt fragments or authentication details.
    throw when (status) {
        400 -> OpenAICompatibleBadRequestException("Codex rejected this request.")
        401, 403 -> codexSignInError()
        402 -> OpenAICompatibleQuotaExhaustedException()
        404 -> OpenAICompatibleModelNotFoundException()
        408, 504 -> OpenAICompatibleTimeoutException()
        413 -> OpenAICompatibleBadRequestException("This Codex conversation is too large to send. Start a shorter conversation or remove large attachments.")
        429 -> OpenAICompatibleRateLimitExceededException()
        503 -> OpenAICompatibleServiceUnavailableException()
        else -> OpenAICompatibleGenericException("Codex request failed (HTTP $status). Try again.")
    }
}
