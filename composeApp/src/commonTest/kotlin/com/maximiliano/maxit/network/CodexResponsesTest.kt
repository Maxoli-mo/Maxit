package com.maximiliano.maxit.network

import com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesRequestDto
import com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(io.ktor.utils.io.InternalAPI::class)
class CodexResponsesTest {
    private val completed = """{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"Summary"}]},{"type":"message","content":[{"type":"output_text","text":"Hello"}]},{"type":"function_call","call_id":"call-1","name":"search","arguments":"{}"}]}}"""

    private fun objectOf(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun tokens(accessToken: String = "token", accountId: String = "account", sessionGeneration: Long = 0) = CodexTokens(
        accessToken = accessToken,
        refreshToken = "refresh",
        accountId = accountId,
        expiresAtMillis = Long.MAX_VALUE,
        sessionGeneration = sessionGeneration,
    )

    private fun client(requests: MutableList<HttpRequestData>, respond: suspend (Int) -> Pair<Int, String>): HttpClient = HttpClient(
        object : HttpClientEngineBase("codex-test") {
            override val config = HttpClientEngineConfig()
            override val dispatcher = Dispatchers.Unconfined
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                requests.add(data)
                val (status, body) = respond(requests.lastIndex)
                return HttpResponseData(
                    statusCode = HttpStatusCode.fromValue(status),
                    requestTime = GMTDate(),
                    headers = headersOf(),
                    version = HttpProtocolVersion.HTTP_1_1,
                    body = ByteReadChannel(body.encodeToByteArray()),
                    callContext = callContext(),
                )
            }
        },
    )

    private fun request() = OpenAIResponsesRequestDto(
        model = "codex-model",
        input = listOf(objectOf("""{"role":"user","content":"Hello"}""")),
    )

    @Test
    fun `Codex body lifts instructions and retains all remaining items and tools`() {
        val input = listOf(
            objectOf("""{"role":"system","content":[{"type":"input_text","text":"System"}]}"""),
            objectOf("""{"role":"developer","content":"Developer"}"""),
            objectOf("""{"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,AA"}]}"""),
            objectOf("""{"role":"assistant","content":[{"type":"output_text","text":"Checking"}]}"""),
            objectOf("""{"type":"function_call","call_id":"call-1","name":"search","arguments":"{}"}"""),
            objectOf("""{"type":"function_call_output","call_id":"call-1","output":"result"}"""),
        )
        val dto = OpenAIResponsesRequestDto(
            input = input,
            model = "codex-model",
            tools = listOf(OpenAIResponsesRequestDto.Tool(name = "search")),
        )
        val body = codexRequestBody(dto)
        assertEquals(JsonPrimitive("System\n\nDeveloper"), body["instructions"])
        assertEquals(JsonArray(input.drop(2)), body["input"])
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(objectOf("""{"summary":"auto"}"""), body["reasoning"])
        assertNotNull(body["tools"])
        assertFalse(body.keys.any { it in setOf("temperature", "max_tokens", "max_output_tokens") })
        assertEquals(JsonPrimitive(""), codexRequestBody(request())["instructions"])
        assertNull(codexRequestBody(dto, supportsSummary = false)["reasoning"])
        val standardBody = Json.encodeToJsonElement(OpenAIResponsesRequestDto.serializer(), dto).jsonObject
        assertFalse(standardBody.keys.any { it in setOf("instructions", "stream", "reasoning") })
    }

    @Test
    fun `SSE reads completion without EOF and preserves text reasoning and calls`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        try {
            channel.writeStringUtf8(": keepalive\r\nevent: response.created\r\ndata: {\"type\":\"response.created\"}\r\n\r\ndata: $completed\r\n\r\n")
            val result = readCodexResponse(channel)
            assertEquals("Hello", result.outputText)
            assertEquals("Summary", result.reasoningSummary)
            assertEquals("call-1", result.functionCalls.single().callId)
            assertEquals("{}", result.functionCalls.single().arguments)
        } finally {
            channel.cancel(null)
        }
    }

    @Test
    fun `SSE supports event field multiline data and final frame without trailing blank line`() = runTest {
        val frame = "event: response.completed\ndata: {\"response\":\ndata: {\"status\":\"completed\",\"output\":[]}}"
        assertEquals("completed", readCodexResponse(ByteReadChannel(frame.encodeToByteArray())).status)
    }

    @Test
    fun `empty terminal output retains completed text calls and opaque reasoning in index order`() = runTest {
        val reasoning = """{"type":"reasoning","id":"reason","summary":[],"encrypted_content":"opaque"}"""
        val message = """{"type":"message","id":"message","role":"assistant","content":[{"type":"output_text","text":"ready"}]}"""
        val call = """{"type":"function_call","id":"item-call","call_id":"call-1","name":"get_local_time","arguments":"{}"}"""
        val frames = listOf(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"message"}}""",
            """{"type":"response.output_text.delta","output_index":1,"delta":"ready"}""",
            """{"type":"response.output_item.done","output_index":2,"item":$call}""",
            """{"type":"response.output_item.done","output_index":0,"item":$reasoning}""",
            """{"type":"response.output_item.done","output_index":1,"item":$message}""",
            """{"type":"response.completed","response":{"status":"completed","output":[],"usage":{"output_tokens":5}}}""",
        )
        val received = StringBuilder()
        val response = readCodexResponse(ByteReadChannel(frames.joinToString("\n\n", postfix = "\n\n") { "data: $it" })) { received.append(it) }
        assertEquals("ready", received.toString())
        assertEquals(received.toString(), response.outputText)
        assertEquals(listOf("reasoning", "message", "function_call"), response.output.map { it.type })
        assertEquals("call-1", response.functionCalls.single().callId)
        assertEquals("{}", response.functionCalls.single().arguments)
        assertEquals(JsonPrimitive("opaque"), response.rawOutput!!.first().jsonObject["encrypted_content"])
        assertEquals(JsonPrimitive(5), response.usage!!["output_tokens"])
    }

    @Test
    fun `terminal output wins over streamed copies without duplicating calls`() = runTest {
        val call = """{"type":"function_call","call_id":"call-1","name":"search","arguments":"{}"}"""
        val body = "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$call}\n\ndata: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"{\"}\n\ndata: $completed\n\n"
        val response = readCodexResponse(ByteReadChannel(body))
        assertEquals("Hello", response.outputText)
        assertEquals(1, response.functionCalls.size)
    }

    @Test
    fun `done items without completion or unfinished items at an empty completion never dispatch`() = runTest {
        val item = """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call","name":"search","arguments":"{}"}}"""
        val unfinished = """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call"}}"""
        val terminal = """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
        for (tail in listOf(item, "$item\n\ndata: $unfinished\n\ndata: $terminal", terminal)) {
            val received = StringBuilder()
            assertFailsWith<StreamInterruptedException> {
                readCodexResponse(ByteReadChannel("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Saved prefix\"}\n\ndata: $tail\n\n")) { received.append(it) }
            }
            assertEquals("Saved prefix", received.toString())
        }
    }

    @Test
    fun `empty terminal recovery rejects gaps and unfinished text or argument fragments`() = runTest {
        val call = """{"type":"function_call","call_id":"call","name":"search","arguments":"{}"}"""
        val first = """{"type":"response.output_item.done","output_index":0,"item":$call}"""
        val text = """{"type":"response.output_text.delta","output_index":1,"delta":"Saved text"}"""
        val arguments = """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{"}"""
        val message = """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[{"type":"output_text","text":"ready"}]}}"""
        val terminal = """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
        for (frames in listOf(
            listOf(first.replace("\"output_index\":0", "\"output_index\":1")),
            listOf(message, first.replace("\"output_index\":0", "\"output_index\":2")),
            listOf(first, text),
            listOf(first, arguments),
            listOf(first, text.replace("\"output_index\":1,", "")),
            listOf(first, arguments.replace("\"output_index\":1,", "")),
            listOf(text.replace("\"output_index\":1", "\"output_index\":0"), first),
        )) {
            val received = StringBuilder()
            assertFailsWith<StreamInterruptedException>(frames.toString()) {
                readCodexResponse(ByteReadChannel((frames + terminal).joinToString("\n\n", postfix = "\n\n") { "data: $it" })) { received.append(it) }
            }
            assertEquals(if (frames.any { "Saved text" in it }) "Saved text" else "", received.toString())
        }
    }

    @Test
    fun `completed fragments without added events still recover complete contiguous output`() = runTest {
        for (text in listOf("ready", " ")) {
            val frames = listOf(
                """{"type":"response.output_text.delta","output_index":0,"delta":${JsonPrimitive(text)}}""",
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)}}]}}""",
                """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{}"}""",
                """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"call","name":"search","arguments":"{}"}}""",
                """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
            )
            val response = readCodexResponse(ByteReadChannel(frames.joinToString("\n\n", postfix = "\n\n") { "data: $it" }))
            assertEquals(text.takeIf { it.isNotBlank() }, response.outputText)
            assertEquals("call", response.functionCalls.single().callId)
        }
    }

    @Test
    fun `invalid completed function calls are rejected in both JSON and SSE`() = runTest {
        val calls = listOf(
            """{"type":"function_call","call_id":"call","name":"search"}""",
            """{"type":"function_call","call_id":"","name":"search","arguments":"{}"}""",
            """{"type":"function_call","call_id":"call","name":"","arguments":"{}"}""",
            """{"type":"function_call","call_id":"call","name":"search","arguments":"{"}""",
            """{"type":"function_call","call_id":"call","name":"search","arguments":"[]"}""",
        )
        for (call in calls) {
            assertFailsWith<StreamInterruptedException> {
                readResponsesBody("""{"status":"completed","output":[$call]}""")
            }
            assertFailsWith<StreamInterruptedException> {
                readCodexResponse(ByteReadChannel("data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$call}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"))
            }
        }
    }

    @Test
    fun `completed Responses calls retain batch identity and argument bounds`() {
        val call = OpenAIResponsesResponseDto.OutputItem(type = "function_call", callId = "call", name = "search", arguments = "{}")
        for (calls in listOf(listOf(call, call), List(257) { call.copy(callId = "call-$it") }, listOf(call.copy(arguments = "{\"text\":\"${"x".repeat(1_048_576)}\"}")))) {
            assertFailsWith<StreamInterruptedException> { validateResponsesTools(OpenAIResponsesResponseDto(output = calls)) }
        }
        validateResponsesTools(OpenAIResponsesResponseDto(output = listOf(call)))
    }

    @Test
    fun `conflicting completed items and invalid output indexes fail without dispatch`() = runTest {
        val message = """{"type":"message","content":[{"type":"output_text","text":"one"}]}"""
        for (index in listOf("-1", "512", "999999999999", "\"bad\"")) {
            assertFailsWith<StreamInterruptedException> {
                readCodexResponse(ByteReadChannel("data: {\"type\":\"response.output_item.done\",\"output_index\":$index,\"item\":$message}\n\n"))
            }
        }
        val body = "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$message}\n\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":${message.replace("one", "two")}}\n\n"
        assertFailsWith<StreamInterruptedException> { readCodexResponse(ByteReadChannel(body)) }
    }

    @Test
    fun `SSE terminal failures and premature EOF are safe errors`() = runTest {
        val frames = listOf(
            """{"type":"response.failed","response":{"error":{"message":"SECRET prompt"}}}""",
            """{"type":"error","message":"SECRET token"}""",
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
            """{"type":"response.completed","response":{"status":"incomplete"}}""",
            """{"type":"response.completed","response":{"error":{"message":"SECRET"}}}""",
            """{"type":"response.completed","response":{"output":"SECRET invalid"}}""",
            "SECRET invalid JSON",
        )
        for (frame in frames) {
            val error = assertFailsWith<OpenAICompatibleApiException> {
                readCodexResponse(ByteReadChannel("data: $frame\n\n".encodeToByteArray()))
            }
            assertFalse(error.message.orEmpty().contains("SECRET"))
            assertNull(error.cause)
        }
        for (body in listOf("", "data: {\"type\":\"response.created\"}\n\n", "data: [DONE]\n\n", "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call\",\"name\":\"search\",\"arguments\":\"{}\"}}\n\ndata: [DONE]\n\n")) {
            val error = assertFailsWith<StreamInterruptedException> { readCodexResponse(ByteReadChannel(body.encodeToByteArray())) }
            assertFalse(error.message.orEmpty().contains("SECRET"))
            assertNull(error.cause)
        }
    }

    @Test
    fun `SSE known terminal codes classify quota rate and expired authentication without raw details`() = runTest {
        for ((code, kind) in listOf(
            "usage_limit_reached" to "quota",
            "insufficient_quota" to "quota",
            "rate_limit_exceeded" to "rate",
            "authentication_error" to "auth",
            "token_expired" to "auth",
            "invalid_token" to "auth",
            "unknown_future_code" to "unknown",
        )) {
            for (frame in listOf(
                """{"type":"response.failed","response":{"error":{"code":"$code","message":"SECRET token and prompt"}}}""",
                """{"type":"error","error":{"type":"$code","message":"SECRET token and prompt"}}""",
                """{"type":"error","code":"$code","message":"SECRET token and prompt"}""",
                """{"type":"response.completed","response":{"status":"failed","error":{"code":"$code","message":"SECRET token and prompt"}}}""",
                """{"type":"response.incomplete","response":{"error":{"code":"$code","message":"SECRET token and prompt"}}}""",
            )) {
                val error = assertFailsWith<Exception> {
                    readCodexResponse(ByteReadChannel("data: $frame\n\n".encodeToByteArray()))
                }
                when (kind) {
                    "quota" -> assertTrue(error is OpenAICompatibleQuotaExhaustedException)

                    "rate" -> assertTrue(error is OpenAICompatibleRateLimitExceededException)

                    "auth" -> {
                        assertTrue(error is CodexAuthException)
                        assertTrue(error.message.orEmpty().contains("Sign in again"))
                    }

                    else -> assertTrue(error is OpenAICompatibleGenericException)
                }
                assertFalse(error.message.orEmpty().contains("SECRET"))
                assertNull(error.cause)
            }
        }
    }

    @Test
    fun `401 refreshes once and retries the identical body with new credentials`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val refreshes = mutableListOf<Pair<String, Boolean>>()
        val http = client(requests) { index -> if (index == 0) 401 to "SECRET" else 200 to "data: $completed\n\n" }
        try {
            val transport = CodexResponses(
                credentials = { instanceId, forceRefresh ->
                    refreshes.add(instanceId to forceRefresh)
                    tokens(if (forceRefresh) "new-token" else "old-token", sessionGeneration = 7)
                },
                client = http,
                currentGeneration = { 7L },
            )
            assertEquals("Hello", transport.responses("instance", request(), "conversation").outputText)
            assertEquals(listOf("instance" to false, "instance" to true), refreshes)
            assertEquals(2, requests.size)
            assertEquals((requests[0].body as TextContent).text, (requests[1].body as TextContent).text)
            assertEquals("Bearer old-token", requests[0].headers["Authorization"])
            assertEquals("Bearer new-token", requests[1].headers["Authorization"])
            for (sent in requests) {
                assertEquals("https://chatgpt.com/backend-api/codex/responses", sent.url.toString())
                assertEquals("account", sent.headers["ChatGPT-Account-Id"])
                assertEquals("maxit", sent.headers["originator"])
                assertEquals("conversation", sent.headers["session-id"])
                assertEquals("responses=experimental", sent.headers["OpenAI-Beta"])
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun `models refreshes auth and adapts the subscription catalog without paid API filtering`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val refreshes = mutableListOf<Boolean>()
        val catalog = """{"models":[{"slug":"codex-model","display_name":"Codex","description":"Included","visibility":"list","supported_in_api":false,"context_window":1234,"supports_reasoning_summary_parameter":false},{"slug":"hidden","visibility":"hide"},{"slug":"none","visibility":"none"},{"slug":"invisible","visibility":"list","visible":false},{"slug":"future","visibility":"future-value"},{"slug":"deprecated","visibility":"deprecated"},{"slug":"missing-visibility"},{"display_name":"No ID"}]}"""
        val http = client(requests) { index ->
            when (index) {
                0 -> 401 to "SECRET"
                1 -> 200 to catalog
                else -> 200 to "data: $completed\n\n"
            }
        }
        try {
            val transport = CodexResponses(
                credentials = { _, forceRefresh ->
                    refreshes.add(forceRefresh)
                    tokens()
                },
                client = http,
            )
            val models = transport.models("instance").data
            assertEquals(listOf("codex-model"), models.map { it.id })
            assertEquals("Codex", models.first().name)
            assertEquals("Included", models.first().description)
            assertEquals(1234L, models.first().context_window)
            assertEquals(CODEX_CLIENT_VERSION, requests.first().url.parameters["client_version"])
            assertEquals("chatgpt.com", requests.first().url.host)
            assertEquals("/backend-api/codex/models", requests.first().url.encodedPath)
            transport.responses("instance", request(), "conversation")
            assertEquals(listOf(false, true, false), refreshes)
            assertNull(objectOf((requests.last().body as TextContent).text)["reasoning"])
        } finally {
            http.close()
        }
    }

    @Test
    fun `repeated unauthorized and failed discovery never retry beyond once or leak backend bodies`() = runTest {
        for ((status, isChat) in listOf(401 to false, 401 to true, 403 to false, 413 to true, 429 to false, 500 to false)) {
            val requests = mutableListOf<HttpRequestData>()
            val http = client(requests) { status to "SECRET token and prompt" }
            try {
                val transport = CodexResponses({ _, _ -> tokens() }, http)
                val error = assertFailsWith<Exception> {
                    if (isChat) transport.responses("instance", request(), "conversation") else transport.models("instance")
                }
                assertEquals(if (status == 401) 2 else 1, requests.size)
                assertFalse(error.message.orEmpty().contains("SECRET"))
                if (status == 401 || status == 403) {
                    assertTrue(error is CodexAuthException)
                    assertTrue(error.message.orEmpty().contains("Sign in again"))
                } else {
                    assertTrue(error is OpenAICompatibleApiException)
                }
                if (status == 413) {
                    assertTrue(error is OpenAICompatibleBadRequestException)
                    assertTrue(error.message.orEmpty().contains("conversation is too large"))
                }
                assertTrue(requests.all { it.url.host == "chatgpt.com" })
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun `oversized requests fail locally and stream failures do not repeat the request`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        var credentialCalls = 0
        val http = client(requests) { 200 to "data: {\"type\":\"response.failed\"}\n\n" }
        try {
            val transport = CodexResponses(
                credentials = { _, _ ->
                    credentialCalls++
                    tokens()
                },
                client = http,
            )
            val large = request().copy(input = listOf(objectOf("""{"role":"user","content":"${"x".repeat(1_048_576)}"}""")))
            assertFailsWith<OpenAICompatibleBadRequestException> { transport.responses("instance", large, "conversation") }
            assertEquals(0, credentialCalls)
            assertTrue(requests.isEmpty())
            assertFailsWith<OpenAICompatibleApiException> { transport.responses("instance", request(), "conversation") }
            assertEquals(1, requests.size)
        } finally {
            http.close()
        }
    }

    @Test
    fun `pending 401 never retries an old prompt into a replacement sign-in`() = runTest {
        for ((replacementAccount, observeGeneration) in listOf("other-account" to true, "account" to true, "other-account" to false)) {
            val requests = mutableListOf<HttpRequestData>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var generation = 0L
            var account = "account"
            var credentialCalls = 0
            val http = client(requests) {
                started.complete(Unit)
                release.await()
                401 to "SECRET"
            }
            try {
                val transport = CodexResponses(
                    credentials = { _, _ ->
                        credentialCalls++
                        tokens(accountId = account, sessionGeneration = if (observeGeneration) generation else 0)
                    },
                    client = http,
                    currentGeneration = { if (observeGeneration) generation else null },
                )
                val pending = async { runCatching { transport.responses("instance", request(), "conversation") } }
                started.await()
                account = replacementAccount
                generation++
                release.complete(Unit)
                assertTrue(pending.await().exceptionOrNull() is CodexAuthException)
                assertEquals(1, requests.size)
                assertEquals(if (observeGeneration) 1 else 2, credentialCalls)
                assertEquals("account", requests.single().headers["ChatGPT-Account-Id"])
            } finally {
                release.complete(Unit)
                http.close()
            }
        }
    }

    @Test
    fun `successful responses and model discovery reject results from a replaced generation`() = runTest {
        for (isModels in listOf(false, true)) {
            val requests = mutableListOf<HttpRequestData>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var generation = 0L
            val http = client(requests) {
                started.complete(Unit)
                release.await()
                200 to if (isModels) """{"models":[{"slug":"codex-model","visibility":"list"}]}""" else "data: $completed\n\n"
            }
            try {
                val transport = CodexResponses(
                    credentials = { _, _ -> tokens(sessionGeneration = generation) },
                    client = http,
                    currentGeneration = { generation },
                )
                val pending = async {
                    runCatching {
                        if (isModels) transport.models("instance") else transport.responses("instance", request(), "conversation")
                    }
                }
                started.await()
                generation++
                release.complete(Unit)
                assertTrue(pending.await().exceptionOrNull() is CodexAuthException)
                assertEquals(1, requests.size)
            } finally {
                release.complete(Unit)
                http.close()
            }
        }
    }

    @Test
    fun `tool rounds bound to an earlier generation fail before credentials or HTTP`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        var generation = 0L
        var credentialCalls = 0
        val snapshot = ServiceCredentials(instanceId = "instance", codexSessionGeneration = generation)
        val http = client(requests) { 200 to "data: $completed\n\n" }
        try {
            val transport = CodexResponses(
                credentials = { _, _ ->
                    credentialCalls++
                    tokens(sessionGeneration = generation)
                },
                client = http,
                currentGeneration = { generation },
            )
            transport.responses(snapshot.instanceId, request(), "conversation", snapshot.codexSessionGeneration)
            generation++
            assertFailsWith<CodexAuthException> {
                transport.responses(snapshot.instanceId, request(), "conversation", snapshot.codexSessionGeneration)
            }
            assertFailsWith<CodexAuthException> { transport.models(snapshot.instanceId, snapshot.codexSessionGeneration) }
            assertEquals(1, credentialCalls)
            assertEquals(1, requests.size)
        } finally {
            http.close()
        }
    }

    @Test
    fun `cancellation from credential acquisition and stream reading propagates`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = client(requests) { 200 to "" }
        try {
            val transport = CodexResponses({ _, _ -> throw CancellationException("Stopped") }, http)
            assertFailsWith<CancellationException> { transport.responses("instance", request(), "conversation") }
            assertTrue(requests.isEmpty())
            val channel = ByteChannel(autoFlush = true)
            channel.cancel(CancellationException("Stopped"))
            assertFailsWith<CancellationException> { readCodexResponse(channel) }
        } finally {
            http.close()
        }
    }
}
