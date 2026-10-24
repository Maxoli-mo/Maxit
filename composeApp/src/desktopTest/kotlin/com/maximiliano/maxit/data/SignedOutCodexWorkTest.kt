package com.maximiliano.maxit.data

import com.maximiliano.maxit.email.EmailPoller
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.CodexAuth
import com.maximiliano.maxit.network.CodexAuthException
import com.maximiliano.maxit.network.GenericNetworkException
import com.maximiliano.maxit.network.Requests
import com.maximiliano.maxit.skills.SkillManager
import com.maximiliano.maxit.sms.SmsPoller
import com.maximiliano.maxit.sms.SmsReader
import com.maximiliano.maxit.sms.SmsSender
import com.maximiliano.maxit.testutil.FakeDataRepository
import com.maximiliano.maxit.testutil.FakeSandboxController
import com.maximiliano.maxit.testutil.FakeSkillStorage
import com.maximiliano.maxit.tools.AppPermission
import com.maximiliano.maxit.tools.NotificationListenerController
import com.maximiliano.maxit.tools.PermissionController
import com.maximiliano.maxit.ui.chat.History
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignedOutCodexWorkTest {
    private class Fixture(scope: TestScope) {
        val settings = AppSettings(MapSettings()).apply {
            setConfiguredServiceInstances(listOf(ServiceInstance("codex", Service.OpenAICodex.id)))
            setSoulText("Test instructions")
        }
        val tasks = TaskStore(settings)
        val heartbeat = HeartbeatManager(settings, tasks)
        val notifications = NotificationStore(settings)
        private val email = EmailStore(settings)
        private val sms = SmsStore(settings)
        private val reader = SmsReader()
        private val sandbox = FakeSandboxController()
        val repository = RemoteDataRepository(
            requests = Requests(),
            appSettings = settings,
            conversationStorage = ConversationStorage(settings, SettingsConversationPersistence(settings)),
            toolExecutor = ToolExecutor { emptyList() },
            memoryStore = MemoryStore(settings),
            taskStore = tasks,
            heartbeatManager = heartbeat,
            emailStore = email,
            emailPoller = EmailPoller(email),
            smsStore = sms,
            smsPoller = SmsPoller(sms, reader),
            smsReader = reader,
            smsPermissionController = PermissionController(AppPermission.READ_SMS),
            smsSendPermissionController = PermissionController(AppPermission.SEND_SMS),
            smsSender = SmsSender(),
            smsDraftStore = SmsDraftStore(settings),
            notificationStore = notifications,
            notificationListenerController = NotificationListenerController(),
            mcpServerManager = McpServerManager(settings),
            skillManager = SkillManager(sandbox, backgroundDispatcher = scope.backgroundScope.coroutineContext, storage = FakeSkillStorage()),
            sandboxController = sandbox,
            codexAuth = CodexAuth(settings),
        )

        // Exercise the real request path, without creating a persisted heartbeat conversation.
        private val schedulerRepository = object : DataRepository by FakeDataRepository() {
            override suspend fun askWithTools(prompt: String, instanceId: String?, conversationIdOverride: String?): String = repository.askWithTools(prompt, instanceId, conversationIdOverride)
        }
        val scheduler = TaskScheduler(
            schedulerRepository,
            taskStore = tasks,
            appSettings = settings,
            heartbeatManager = heartbeat,
            notificationStore = notifications,
            backgroundDispatcher = scope.backgroundScope.coroutineContext,
        )
    }

    @Test
    fun `signed out background and silent requests fail instead of returning empty success`() = runTest {
        val f = Fixture(this)
        assertFailsWith<GenericNetworkException> { f.repository.askWithTools("Run this task") }
        assertFailsWith<GenericNetworkException> { f.repository.askSilently("Summarize this") }
    }

    @Test
    fun `scheduled task stays pending and records failure when its only account is signed out`() = runTest {
        val f = Fixture(this)
        f.settings.setSchedulingEnabled(true)
        f.tasks.addTask("Task", "Run this task", scheduledAtEpochMs = 1)
        f.scheduler.start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        val task = f.tasks.getAllTasks().single()
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(1, task.consecutiveFailures)
        assertFalse(task.recentExecutions.single().success)
    }

    @Test
    fun `failed heartbeat keeps pending notifications instead of acknowledging unread work`() = runTest {
        val f = Fixture(this)
        f.settings.setSchedulingEnabled(true)
        f.heartbeat.saveConfig(HeartbeatConfig(enabled = true))
        val notification = NotificationRecord("one", "test.app", "Test", "Unread", "Keep this", postedAtEpochMs = 1)
        f.notifications.addPending(notification)
        f.scheduler.triggerHeartbeatNow()
        assertFalse(f.heartbeat.getHeartbeatLog().single().success)
        assertEquals(listOf(notification), f.notifications.getPending())
    }

    @Test
    fun `failed silent compaction preserves older turns while history still fits`() = runTest {
        val f = Fixture(this)
        // This family disables tools, so prompt construction does not need platform tool services.
        f.settings.settings.putString(Service.OpenAICodex.modelIdKey, "gemma:2b")
        val maxChars = ModelCatalog.estimateContextWindow("gemma:2b") * 4
        val history = List(6) { History(role = History.Role.USER, content = "x".repeat(maxChars / 8)) }
        assertTrue(history.sumOf { it.content.length } > maxChars * 0.7)
        f.repository.chatHistory.value = history
        assertFailsWith<CodexAuthException> { f.repository.ask(question = null, files = emptyList()) }
        assertEquals(history, f.repository.chatHistory.value)
    }
}
