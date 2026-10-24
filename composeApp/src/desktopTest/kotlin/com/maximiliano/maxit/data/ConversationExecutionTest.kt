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
        fun send(exchange: HttpExchange, content: String) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(content.toByteArray())
            exchange.responseBody.flush()
        }
        fun final(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        fun tool(step: Int) = "{\"index\":0,\"id\":\"call-$step\",\"function\":{\"name\":\"get_local_time\",\"arguments\":\"{\\\"step\\\":$step}\"}}"
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
