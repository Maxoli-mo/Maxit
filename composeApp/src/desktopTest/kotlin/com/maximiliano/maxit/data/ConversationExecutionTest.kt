package com.maximiliano.maxit.data

import com.maximiliano.maxit.email.EmailPoller
import com.maximiliano.maxit.inference.DownloadError
import com.maximiliano.maxit.inference.DownloadedModel
import com.maximiliano.maxit.inference.EngineState
import com.maximiliano.maxit.inference.InferenceMessage
import com.maximiliano.maxit.inference.InferenceTimeoutException
import com.maximiliano.maxit.inference.LocalInferenceEngine
import com.maximiliano.maxit.inference.LocalModel
import com.maximiliano.maxit.inference.LocalTool
import com.maximiliano.maxit.inference.ModelImportError
import com.maximiliano.maxit.inference.ModelImportResult
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.CodexAuth
import com.maximiliano.maxit.network.Requests
import com.maximiliano.maxit.network.StreamInterruptedException
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.network.tools.ToolSchema
import com.maximiliano.maxit.skills.SkillManager
import com.maximiliano.maxit.sms.SmsPoller
import com.maximiliano.maxit.sms.SmsReader
import com.maximiliano.maxit.sms.SmsSender
import com.maximiliano.maxit.testutil.FakeSandboxController
import com.maximiliano.maxit.testutil.FakeSkillStorage
import com.maximiliano.maxit.tools.AppPermission
import com.maximiliano.maxit.tools.NotificationListenerController
import com.maximiliano.maxit.tools.PermissionController
import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real HTTP/SSE + real durable store. No external services or model credentials. */
class ConversationExecutionTest {
    private class Fixture(
        handler: (HttpExchange, Int) -> Unit,
        execute: suspend (Map<String, Any>) -> Any = { "result ${it["step"]}" },
        localEngine: LocalInferenceEngine? = null,
        toolName: String = "get_local_time",
        available: () -> Boolean = { true },
    ) : AutoCloseable {
        val calls = AtomicInteger()
        val requestBodies = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool { Thread(it, "fake-provider").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = workers
            createContext("/v1/chat/completions") { exchange ->
                try {
                    requestBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                    handler(exchange, calls.incrementAndGet())
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val settings = AppSettings(MapSettings()).apply {
            setConfiguredServiceInstances(listOf(ServiceInstance("test", Service.OpenAICompatible.id)))
            setInstanceModelId("test", "test-agent")
            setInstanceBaseUrl("test", "http://127.0.0.1:${server.address.port}/v1")
            setSoulText("Test instructions")
        }
        val tasks = TaskStore(settings)
        private val memory = MemoryStore(settings)
        private val heartbeat = HeartbeatManager(settings, tasks)
        private val email = EmailStore(settings)
        private val sms = SmsStore(settings)
        private val reader = SmsReader()
        private val sandbox = FakeSandboxController()
        private val mcp = McpServerManager(settings)
        private val skills = SkillManager(sandbox, storage = FakeSkillStorage())
        val storage = ConversationStorage(settings, SettingsConversationPersistence(settings)).apply { loadConversations() }
        val repository = RemoteDataRepository(
            requests = Requests(), appSettings = settings, conversationStorage = storage,
            toolExecutor = ToolExecutor {
                if (available()) {
                    listOf(object : Tool {
                        override val schema = ToolSchema(toolName, "Test action", emptyMap())
                        override suspend fun execute(args: Map<String, Any>): Any = execute(args)
                    })
                } else {
                    emptyList()
                }
            },
            memoryStore = memory, taskStore = tasks, heartbeatManager = heartbeat,
            emailStore = email, emailPoller = EmailPoller(email), smsStore = sms, smsPoller = SmsPoller(sms, reader), smsReader = reader,
            smsPermissionController = PermissionController(AppPermission.READ_SMS), smsSendPermissionController = PermissionController(AppPermission.SEND_SMS), smsSender = SmsSender(), smsDraftStore = SmsDraftStore(settings),
            notificationStore = NotificationStore(settings), notificationListenerController = NotificationListenerController(),
            mcpServerManager = mcp, skillManager = skills, sandboxController = sandbox, codexAuth = CodexAuth(settings),
            localInferenceEngine = localEngine,
        )
        init {
            startKoin {
                modules(
                    module {
                        single { settings }
                        single { tasks }
                        single { memory }
                        single { email }
                        single { mcp }
                        single { skills }
                    },
                )
            }
        }
        override fun close() {
            server.stop(0)
            workers.shutdownNow()
            stopKoin()
        }
    }

    private class ControlledNativeEngine(private val gate: CompletableDeferred<Unit>? = null) : LocalInferenceEngine {
        var calls = 0
        var failure: Exception? = InferenceTimeoutException()
        private val model = DownloadedModel("native-test", "Native test", "unused.litertlm", 0)
        override val supportsToolExecution = false
        override val engineState = MutableStateFlow(EngineState.READY)
        override val downloadingModelId = MutableStateFlow<String?>(null)
        override val downloadProgress = MutableStateFlow<Float?>(null)
        override val downloadError = MutableStateFlow<DownloadError?>(null)
        override val importingFileName = MutableStateFlow<String?>(null)
        override val importProgress = MutableStateFlow<Float?>(null)
        override val importError = MutableStateFlow<ModelImportError?>(null)
        override val currentModelId = model.id
        override suspend fun initialize(model: DownloadedModel, contextTokens: Int) = Unit
        override suspend fun release() = Unit
        override fun releaseInBackground() = Unit
        override fun getDownloadedModels() = listOf(model)
        override fun getAvailableModels() = emptyList<LocalModel>()
        override fun getImportedLocalModels() = emptyList<LocalModel>()
        override fun getFreeSpaceBytes() = Long.MAX_VALUE
        override fun startDownload(model: LocalModel) = Unit
        override fun cancelDownload() = Unit
        override suspend fun deleteModel(modelId: String) = Unit
        override suspend fun importModel(source: PlatformFile): ModelImportResult = error("Not used")
        override fun cancelImport() = Unit
        override suspend fun chat(messages: List<InferenceMessage>, systemPrompt: String?, tools: List<LocalTool>): String = error("Streaming expected")
        override suspend fun chatStreaming(messages: List<InferenceMessage>, systemPrompt: String?, tools: List<LocalTool>, onText: suspend (String) -> Unit): String {
            calls++
            onText("Saved native prefix")
            gate?.await()
            failure?.let { throw it }
            return "Saved native prefix"
        }
    }

    private companion object {
        fun send(exchange: HttpExchange, content: String, contentType: String? = "text/event-stream") {
            contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(content.toByteArray())
            exchange.responseBody.flush()
        }
        fun final(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(text)}},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        fun reply(exchange: HttpExchange, text: String = "", call: String? = null, streaming: Boolean = true) {
            val calls = if (call == null) "" else """, "tool_calls":[$call]"""
            val body = """{"choices":[{"${if (streaming) "delta" else "message"}":{"content":${JsonPrimitive(text)}$calls},"finish_reason":"${if (call == null) "stop" else "tool_calls"}"}]}"""
            send(exchange, if (streaming) "data: $body\n\ndata: [DONE]\n\n" else body, if (streaming) "text/event-stream" else "application/json")
        }
        fun tool(step: Int) = "{\"index\":0,\"id\":\"call-$step\",\"function\":{\"name\":\"get_local_time\",\"arguments\":\"{\\\"step\\\":$step}\"}}"
    }

    @Test
    fun `missing or incorrect stream Content-Type preserves native calls and final answers`() = runBlocking {
        withTimeout(20_000) {
            for (contentType in listOf(null, "application/json")) {
                val executions = AtomicInteger()
                Fixture(handler = { exchange, index ->
                    val body = if (index == 1) "data: {\"choices\":[{\"delta\":{\"tool_calls\":[${tool(1)}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n" else final("Done")
                    send(exchange, body, contentType)
                }, execute = { executions.incrementAndGet() }).use { f ->
                    assertEquals("Done", f.repository.ask("Check the time", emptyList())?.content)
                    assertEquals(2, f.calls.get())
                    assertEquals(1, executions.get())
                    assertEquals(1, SettingsConversationPersistence(f.settings).loadAll().single().messages.count { it.role == "tool" })
                }
            }
        }
    }

    @Test
    fun `a real streamed tool run exceeds fifteen rounds and reloads every exchange`() = runBlocking {
        withTimeout(30_000) {
            Fixture({ exchange, index ->
                if (index <= 22) {
                    send(exchange, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[${tool(index)}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
                } else {
                    send(exchange, final("Finished all rounds"))
                }
            }).use { f ->
                var accepted = 0
                withContext(
                    ConversationInputAccepted {
                        accepted++
                        val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                        assertTrue(saved.any { it.role == "user" && it.content == "Complete the task" })
                        assertEquals(0, f.calls.get())
                    },
                ) {
                    f.repository.ask("Complete the task", emptyList())
                }
                assertEquals(1, accepted)
                assertEquals(23, f.calls.get())
                val reloaded = ConversationStorage(f.settings, SettingsConversationPersistence(f.settings))
                reloaded.loadConversations()
                val messages = reloaded.conversations.value.single().messages
                assertEquals(22, messages.count { it.role == "tool" })
                assertEquals("Finished all rounds", messages.last().content)
                assertTrue(messages.filter { it.isStatusMessage }.all { it.state == MessageState.COMPLETE })
            }
        }
    }

    @Test
    fun `text tool envelope is corrected through native calls in SSE and JSON without dispatching text`() = runBlocking {
        withTimeout(30_000) {
            val bare = """{"tool_calls":[{"name":"load_skill","arguments":{"id":"beautiful-markdown"}}]}"""
            val native = """{"id":"load-1","type":"function","function":{"name":"load_skill","arguments":"{\"id\":\"beautiful-markdown\"}"}}"""
            for (streaming in listOf(true, false)) {
                val executions = AtomicInteger()
                Fixture(handler = { exchange, index ->
                    when (index) {
                        1 -> reply(exchange, text = bare, streaming = streaming)
                        2 -> reply(exchange, call = native, streaming = streaming)
                        else -> reply(exchange, text = "# Markdown example", streaming = streaming)
                    }
                }, toolName = "load_skill", execute = {
                    assertEquals("beautiful-markdown", it["id"])
                    executions.incrementAndGet()
                    "Skill instructions"
                }).use { f ->
                    assertEquals("# Markdown example", f.repository.ask("Show me formatted Markdown", emptyList())?.content)
                    assertEquals(3, f.calls.get())
                    assertEquals(1, executions.get())
                    val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                    assertTrue(saved.single { it.content == bare }.toolCalls.isNullOrEmpty())
                    assertEquals(1, saved.count { it.role == "tool" })
                    val correction = Json.parseToJsonElement(f.requestBodies[1]).jsonObject.getValue("messages").jsonArray
                    assertTrue(correction.any { it.jsonObject["role"] == JsonPrimitive("assistant") && it.jsonObject["content"] == JsonPrimitive(bare) })
                    assertTrue("not a tool invocation" in correction.first().jsonObject.getValue("content").toString())
                    assertTrue(saved.filter { it.isStatusMessage }.all { it.state == MessageState.COMPLETE })
                }
            }
        }
    }

    @Test
    fun `repeated text envelopes pause after one correction without executing or failing over`() = runBlocking {
        withTimeout(20_000) {
            for (bare in listOf(
                """{"tool_calls":[{"name":"get_local_time","arguments":{}}]}""",
                """{"tool_calls":[{"name":"get_local_time","arguments":{"example":"<tool_call><function=get_local_time></function></tool_call>"}}]}""",
            )) {
                val executions = AtomicInteger()
                Fixture(handler = { exchange, _ -> reply(exchange, text = bare) }, execute = { executions.incrementAndGet() }).use { f ->
                    assertNull(f.repository.ask("Check the time", emptyList()))
                    assertEquals(2, f.calls.get())
                    assertEquals(0, executions.get())
                    val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                    assertEquals(2, saved.count { it.content == bare })
                    assertTrue(saved.all { it.toolCalls.isNullOrEmpty() })
                    assertTrue(saved.any { it.isStatusMessage && it.state == MessageState.PAUSED && "not executed" in it.content })
                }
            }
        }
    }

    @Test
    fun `correction preserves earlier outcomes and never replays completed actions`() = runBlocking {
        withTimeout(20_000) {
            val bare = """{"tool_calls":[{"name":"get_local_time","arguments":{"step":2}}]}"""
            val executed = CopyOnWriteArrayList<Int>()
            Fixture(handler = { exchange, index ->
                when (index) {
                    1 -> reply(exchange, call = tool(1))
                    2 -> reply(exchange, text = bare)
                    3 -> reply(exchange, call = tool(2))
                    else -> reply(exchange, text = "Done")
                }
            }, execute = {
                executed.add((it["step"] as Number).toInt())
                "Recorded result"
            }).use { f ->
                assertEquals("Done", f.repository.ask("Check twice", emptyList())?.content)
                assertEquals(listOf(1, 2), executed.toList())
                assertEquals(4, f.calls.get())
                val correction = Json.parseToJsonElement(f.requestBodies[2]).jsonObject.getValue("messages").jsonArray
                assertEquals(1, correction.count { it.jsonObject["role"] == JsonPrimitive("tool") })
                assertTrue("Recorded result" in correction.toString())
                assertEquals(2, SettingsConversationPersistence(f.settings).loadAll().single().messages.count { it.role == "tool" })
            }
        }
    }

    @Test
    fun `native calls recovered from text still require exact approval`() = runBlocking {
        withTimeout(30_000) {
            val bare = """{"tool_calls":[{"name":"open_url","arguments":{"url":"https://example.invalid"}}]}"""
            val native = """{"id":"open-1","function":{"name":"open_url","arguments":"{\"url\":\"https://example.invalid\"}"}}"""
            for (approved in listOf(true, false)) {
                val executions = AtomicInteger()
                Fixture(handler = { exchange, index ->
                    when (index) {
                        1 -> reply(exchange, text = bare)
                        2 -> reply(exchange, call = native)
                        else -> reply(exchange, text = "Finished")
                    }
                }, toolName = "open_url", execute = {
                    executions.incrementAndGet()
                    "Opened"
                }).use { f ->
                    val run = async(Dispatchers.Default) { f.repository.ask("Open the example URL", emptyList()) }
                    val pending = f.repository.toolApprovals.first { it.isNotEmpty() }.single()
                    assertEquals(0, executions.get())
                    assertEquals("open_url", pending.name)
                    assertEquals("""{"url":"https://example.invalid"}""", pending.arguments)
                    f.repository.resolveToolApproval(pending.id, approved)
                    assertEquals("Finished", run.await()?.content)
                    assertEquals(if (approved) 1 else 0, executions.get())
                    assertEquals(3, f.calls.get())
                }
            }
            Fixture(handler = { exchange, index ->
                if (index == 1) reply(exchange, text = bare) else reply(exchange, call = native)
            }, toolName = "open_url", execute = { error("Unattended correction must not bypass approval") }).use { f ->
                assertFailsWith<RunPausedException> { f.repository.askWithTools("Open the example URL") }
                assertEquals(2, f.calls.get())
                assertTrue(f.repository.toolApprovals.value.isEmpty())
                val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                assertTrue(saved.any { it.isStatusMessage && it.state == MessageState.PAUSED })
                assertTrue(saved.single { it.toolCallId == "open-1" }.content.contains("not_started"))
            }
        }
    }

    @Test
    fun `quoted examples and ordinary JSON remain answers without correction or execution`() = runBlocking {
        withTimeout(20_000) {
            val bare = """{"tool_calls":[{"name":"get_local_time","arguments":{}}]}"""
            for (example in listOf("```json\n$bare\n```", "Example: $bare", """{"example":$bare}""")) {
                Fixture(handler = { exchange, _ -> reply(exchange, text = example) }, execute = { error("Examples must never run") }).use { f ->
                    assertEquals(example, f.repository.ask("Show a JSON example, do not run it", emptyList())?.content)
                    assertEquals(1, f.calls.get())
                    assertTrue(f.storage.conversations.value.single().messages.all { it.toolCalls.isNullOrEmpty() })
                }
            }
            Fixture(handler = { exchange, index -> reply(exchange, text = if (index == 1) bare else "```json\n$bare\n```") }, execute = { error("Bare examples must not be dispatched") }).use { f ->
                assertEquals("```json\n$bare\n```", f.repository.ask("Show an example tool JSON object, do not run it", emptyList())?.content)
                assertEquals(2, f.calls.get())
                assertTrue(f.storage.conversations.value.single().messages.all { it.toolCalls.isNullOrEmpty() })
            }
        }
    }

    @Test
    fun `reasoning and quoted wrapped examples never execute in JSON or SSE`() = runBlocking {
        withTimeout(20_000) {
            val wrapped = "<tool_call><function=get_local_time></function></tool_call>"
            for (sse in listOf(false, true)) {
                for ((field, text) in listOf(
                    "reasoning_content" to wrapped,
                    "content" to "```xml\n$wrapped\n```",
                    "content" to "~~~xml\n$wrapped\n~~~",
                    "content" to "`$wrapped`",
                    "content" to "\"$wrapped\"",
                    "content" to "> $wrapped",
                    "content" to "Example: $wrapped",
                    "content" to "{\"example\":\"$wrapped\"}",
                )) {
                    Fixture(handler = { exchange, _ ->
                        if (sse) {
                            send(exchange, "data: {\"choices\":[{\"delta\":{\"$field\":${JsonPrimitive(text)}},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        } else {
                            send(exchange, """{"choices":[{"message":{"role":"assistant","$field":${JsonPrimitive(text)}},"finish_reason":"stop"}]}""", "application/json")
                        }
                    }, execute = { error("Reasoning and examples must never execute") }).use { f ->
                        f.repository.ask("Show an example. Do not execute any tools.", emptyList())
                        assertEquals(1, f.calls.get())
                        val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                        assertTrue(saved.any { it.content == text && it.isThinking == (field == "reasoning_content") })
                        assertTrue(saved.all { it.toolCalls.isNullOrEmpty() })
                    }
                }
            }
        }
    }

    @Test
    fun `standalone legacy wrappers still work and native calls retain precedence`() = runBlocking {
        withTimeout(20_000) {
            val wrapped = "<tool_call><function=get_local_time><parameter=step>2</parameter></function></tool_call>"
            for (sse in listOf(false, true)) {
                for (native in listOf(false, true)) {
                    val steps = mutableListOf<String>()
                    Fixture(handler = { exchange, index ->
                        if (index != 1) {
                            reply(exchange, text = "Done")
                        } else if (sse) {
                            val calls = if (native) ",\"tool_calls\":[${tool(1)}]" else ""
                            send(exchange, "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(wrapped)}$calls},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        } else {
                            reply(exchange, text = wrapped, call = if (native) tool(1) else null, streaming = false)
                        }
                    }, execute = { args -> steps.add(args["step"].toString()) }).use { f ->
                        assertEquals("Done", f.repository.ask("Check the time", emptyList())?.content)
                        assertEquals(listOf(if (native) "1" else "2"), steps)
                        assertEquals(2, f.calls.get())
                    }
                }
            }
        }
    }

    @Test
    fun `reasoning-only JSON never triggers correction or execution`() = runBlocking {
        withTimeout(20_000) {
            val bare = """{"tool_calls":[{"name":"get_local_time","arguments":{}}]}"""
            Fixture(handler = { exchange, _ ->
                send(exchange, """{"choices":[{"message":{"role":"assistant","reasoning_content":${JsonPrimitive(bare)}},"finish_reason":"stop"}]}""", "application/json")
            }, execute = { error("Reasoning must not dispatch tools") }).use { f ->
                f.repository.ask("Check the time", emptyList())
                assertEquals(1, f.calls.get())
                val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                assertTrue(saved.any { it.content == bare && it.isThinking })
                assertTrue(saved.all { it.toolCalls.isNullOrEmpty() })
                assertTrue(saved.filter { it.isStatusMessage }.all { it.state == MessageState.COMPLETE })
            }
        }
    }

    @Test
    fun `native calls take precedence over accompanying text envelopes without duplicate dispatch`() = runBlocking {
        withTimeout(20_000) {
            val bare = """{"tool_calls":[{"name":"get_local_time","arguments":{"step":1}}]}"""
            val executions = AtomicInteger()
            Fixture(handler = { exchange, index ->
                if (index == 1) reply(exchange, text = bare, call = tool(1)) else reply(exchange, text = "Done")
            }, execute = { executions.incrementAndGet() }).use { f ->
                assertEquals("Done", f.repository.ask("Check the time", emptyList())?.content)
                assertEquals(1, executions.get())
                assertEquals(2, f.calls.get())
                assertFalse("not a tool invocation" in f.requestBodies[1])
            }
        }
    }

    @Test
    fun `disconnect during correction preserves text without another request or tool dispatch`() = runBlocking {
        withTimeout(20_000) {
            val bare = """{"tool_calls":[{"name":"get_local_time","arguments":{}}]}"""
            Fixture(handler = { exchange, index ->
                if (index == 1) reply(exchange, text = bare) else send(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"Correction prefix\",\"tool_calls\":[{\"index\":0,\"id\":\"unfinished\",\"function\":{\"name\":\"get_local_time\",\"arguments\":\"{\"}}]}}]}\n\n")
            }, execute = { error("Unfinished calls must never run") }).use { f ->
                assertFailsWith<StreamInterruptedException> { f.repository.ask("Check the time", emptyList()) }
                assertEquals(2, f.calls.get())
                val saved = SettingsConversationPersistence(f.settings).loadAll().single().messages
                assertTrue(saved.any { it.content == bare })
                assertTrue(saved.any { it.content == "Correction prefix" })
                assertTrue(saved.all { it.toolCalls.isNullOrEmpty() })
                assertTrue(saved.any { it.isStatusMessage && it.state == MessageState.FAILED })
            }
        }
    }

    @Test
    fun `Stop preserves each completed parallel outcome and marks the unfinished action unknown`() = runBlocking {
        withTimeout(20_000) {
            Fixture({ exchange, _ ->
                val second = tool(2).replace("\"index\":0", "\"index\":1")
                send(exchange, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[${tool(1)},$second]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
            }, execute = { if ((it["step"] as Number).toInt() == 1) "completed first" else awaitCancellation() }).use { f ->
                val job = launch(Dispatchers.Default) { f.repository.ask("Run two actions", emptyList()) }
                f.repository.savedConversations.first { conversations -> conversations.any { it.messages.any { message -> message.toolCallId == "call-1" && message.content == "completed first" } } }
                job.cancelAndJoin()
                val messages = f.storage.conversations.value.single().messages
                assertEquals("completed first", messages.single { it.toolCallId == "call-1" }.content)
                assertTrue(messages.single { it.toolCallId == "call-2" }.content.contains("outcome_unknown"))
                assertEquals(1, f.calls.get())
            }
        }
    }

    @Test
    fun `late streamed callbacks stay in their original conversation after navigation`() = runBlocking {
        withTimeout(20_000) {
            val release = CompletableDeferred<Unit>()
            Fixture({ exchange, index ->
                if (index == 1) {
                    send(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"Old partial\"}}]}\n\n")
                    runBlocking { release.await() }
                    exchange.responseBody.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                } else {
                    send(exchange, final("New answer"))
                }
            }).use { f ->
                val job = launch(Dispatchers.Default) { f.repository.ask("Old request", emptyList()) }
                f.repository.savedConversations.first { it.any { row -> row.messages.any { message -> message.content == "Old partial" } } }
                val originalId = f.repository.currentConversationId.value
                f.repository.startNewChat()
                release.complete(Unit)
                job.join()
                assertTrue(f.repository.chatHistory.value.isEmpty())
                f.repository.ask("New request", emptyList())
                assertNotEquals(originalId, f.repository.currentConversationId.value)
                assertFalse(f.repository.chatHistory.value.any { it.content == "Old partial" || it.content == "Old request" })
                assertTrue(f.storage.conversations.value.single { it.id == originalId }.messages.any { it.content == "Old partial" })
            }
        }
    }

    @Test
    fun `availability races never manufacture approval for foreground or unattended work`() = runBlocking {
        withTimeout(20_000) {
            for (interactive in listOf(true, false)) {
                val lookups = AtomicInteger()
                val executions = AtomicInteger()
                Fixture(
                    handler = { exchange, index ->
                        if (index == 1) {
                            val action = tool(1).replace("get_local_time", "write_file")
                            send(exchange, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[$action]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
                        } else {
                            send(exchange, final("Unexpected dispatch"))
                        }
                    },
                    execute = {
                        executions.incrementAndGet()
                        "unexpected"
                    },
                    toolName = "write_file",
                    available = { lookups.incrementAndGet() > 1 },
                ).use { f ->
                    if (interactive) {
                        assertNull(f.repository.ask("Do the action", emptyList()))
                    } else {
                        assertFailsWith<RunPausedException> { f.repository.askWithTools("Do the action") }
                    }
                    assertEquals(0, executions.get())
                    assertEquals(1, f.calls.get())
                    assertTrue(f.repository.toolApprovals.value.isEmpty())
                    val messages = SettingsConversationPersistence(f.settings).loadAll().single().messages
                    assertTrue(messages.any { it.isStatusMessage && it.state == MessageState.PAUSED })
                    assertTrue(messages.single { it.toolCallId == "call-1" }.content.contains("not_started"))
                }
            }
        }
    }

    @Test
    fun `regeneration waits for background ownership before superseding its completed attempt`() = runBlocking {
        withTimeout(20_000) {
            val release = CompletableDeferred<Unit>()
            Fixture({ exchange, index ->
                if (index == 1) {
                    send(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"Background partial\"}}]}\n\n")
                    runBlocking { release.await() }
                    exchange.responseBody.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                } else {
                    send(exchange, final("Regenerated answer"))
                }
            }).use { f ->
                val id = f.repository.getOrCreateHeartbeatConversationId()
                val background = async(Dispatchers.Default) { f.repository.askWithTools("Scheduled request", conversationIdOverride = id) }
                f.repository.savedConversations.first { rows -> rows.any { row -> row.messages.any { it.content == "Background partial" } } }
                f.repository.loadConversation(id)
                val regeneration = async(start = CoroutineStart.UNDISPATCHED) { f.repository.regenerate() }
                assertFalse(f.storage.conversations.value.single { it.id == id }.messages.any { it.superseded })
                release.complete(Unit)
                assertEquals("Background partial", background.await())
                val answer = assertNotNull(regeneration.await())
                assertEquals("Regenerated answer", answer.content)
                val saved = SettingsConversationPersistence(f.settings).loadAll().single { it.id == id }.messages
                val previous = saved.single { it.content == "Background partial" }
                assertTrue(previous.superseded)
                assertTrue(saved.single { it.isStatusMessage && it.runId == previous.runId }.superseded)
                assertFalse(saved.single { it.id == answer.id }.superseded)
                assertNotEquals(previous.runId, answer.runId)
                val sent = Json.parseToJsonElement(f.requestBodies.last()).jsonObject.getValue("messages").jsonArray
                assertFalse(sent.any { (it.jsonObject["content"] as? JsonPrimitive)?.content == "Background partial" })
                assertTrue("(replaced attempt; effects are not undone): Background partial" in f.requestBodies.last())
                assertTrue("Scheduled request" in f.requestBodies.last())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `deleting a real active background transcript pauses only its occurrence and never resurrects it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = ControlledNativeEngine(gate)
        Fixture(handler = { _, _ -> error("Native work must not use HTTP") }, localEngine = engine).use { f ->
            f.settings.setConfiguredServiceInstances(listOf(ServiceInstance("native", Service.LiteRT.id)))
            f.settings.setInstanceModelId("native", "native-test")
            f.settings.setSchedulingEnabled(true)
            val first = f.tasks.addTask("First", "Blocked report", 1)
            val second = f.tasks.addTask("Second", "Unrelated report", 1)
            val owner = Job(backgroundScope.coroutineContext[Job])
            val scheduler = TaskScheduler(f.repository, taskStore = f.tasks, appSettings = f.settings, backgroundDispatcher = StandardTestDispatcher(testScheduler) + owner)
            try {
                scheduler.start()
                runCurrent()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(TaskStatus.RUNNING, f.tasks.getAllTasks().first { it.id == first.id }.status)
                val deletedId = f.storage.conversations.value.single().id
                assertTrue(f.storage.conversations.value.single().messages.any { it.content == "Saved native prefix" })
                f.repository.deleteConversation(deletedId)
                engine.failure = null
                gate.complete(Unit)
                runCurrent()
                assertEquals(TaskStatus.PAUSED, f.tasks.getAllTasks().first { it.id == first.id }.status)
                assertEquals(TaskStatus.COMPLETED, f.tasks.getAllTasks().first { it.id == second.id }.status)
                assertNotNull(scheduler.executionError.value)
                assertEquals(2, engine.calls)
                val third = f.tasks.addTask("Third", "Next tick", 1)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(TaskStatus.COMPLETED, f.tasks.getAllTasks().first { it.id == third.id }.status)
                assertEquals(3, engine.calls)
                assertFalse(SettingsConversationPersistence(f.settings).loadAll().any { it.id == deletedId })
            } finally {
                gate.complete(Unit)
                owner.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `scheduled native partial output pauses instead of automatically retrying`() = runTest {
        val engine = ControlledNativeEngine()
        Fixture(handler = { _, _ -> error("Native work must not use HTTP") }, localEngine = engine).use { f ->
            f.settings.setConfiguredServiceInstances(listOf(ServiceInstance("native", Service.LiteRT.id)))
            f.settings.setInstanceModelId("native", "native-test")
            f.settings.setSchedulingEnabled(true)
            val task = f.tasks.addTask("Native task", "Produce a report", 1)
            val owner = Job(backgroundScope.coroutineContext[Job])
            val scheduler = TaskScheduler(f.repository, taskStore = f.tasks, appSettings = f.settings, backgroundDispatcher = StandardTestDispatcher(testScheduler) + owner)
            try {
                scheduler.start()
                runCurrent()
                advanceTimeBy(60_000)
                runCurrent()
                val paused = f.tasks.getAllTasks().single { it.id == task.id }
                assertEquals(TaskStatus.PAUSED, paused.status)
                assertFalse(paused.recentExecutions.single().success)
                assertEquals(1, engine.calls)
                val messages = SettingsConversationPersistence(f.settings).loadAll().single().messages
                assertTrue(messages.any { it.role == "assistant" && it.content == "Saved native prefix" })
                assertTrue(messages.none { !it.toolCalls.isNullOrEmpty() })
                advanceTimeBy(600_000)
                runCurrent()
                assertEquals(1, engine.calls)
                assertEquals(TaskStatus.PAUSED, f.tasks.getAllTasks().single().status)
                assertEquals(0, f.calls.get())
            } finally {
                owner.cancel()
            }
        }
    }
}
