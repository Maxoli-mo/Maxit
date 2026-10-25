package com.maximiliano.maxit.ui.chat

import app.cash.turbine.test
import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.Conversation
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.data.ToolApprovalController
import com.maximiliano.maxit.data.ToolApprovalRequest
import com.maximiliano.maxit.data.UiSubmission
import com.maximiliano.maxit.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatNotificationTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeDataRepository()

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun `response notifications default on and persist the off switch`() {
        val settings = MapSettings()
        val appSettings = AppSettings(settings)
        assertEquals(true, appSettings.isLlmNotificationsEnabled())
        appSettings.setLlmNotificationsEnabled(false)
        assertEquals(false, AppSettings(settings).isLlmNotificationsEnabled())
    }

    @Test fun `every new pending approval is notified once and removals are forgotten`() = runTest {
        val approvals = MutableStateFlow(emptyList<ToolApprovalRequest>())
        var enabled = true
        var unfocused = true
        val notifications = mutableListOf<List<String?>>()
        val repository = object : DataRepository by fake {
            override val toolApprovals = approvals
            override fun isLlmNotificationsEnabled() = enabled
        }
        val vm = ChatViewModel(
            repository,
            TaskScheduler(repository, enabled = false),
            dispatcher,
            shouldNotify = { unfocused },
            notify = { title, body, chat, request -> notifications.add(listOf(title, body, chat, request)) },
        )
        dispatcher.scheduler.advanceUntilIdle()
        val first = ToolApprovalRequest("one", "chat-a", "shell", "{}")
        val second = ToolApprovalRequest("two", "chat-b", "email", "{}")
        approvals.value = listOf(first, second)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("one", "two"), notifications.map { it[3] })
        assertEquals(listOf("chat-a", "chat-b"), notifications.map { it[2] })
        approvals.value = listOf(second)
        dispatcher.scheduler.advanceUntilIdle()
        approvals.value = listOf(first, second)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("one", "two", "one"), notifications.map { it[3] })
        enabled = false
        approvals.value = listOf(second)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, notifications.size)
        enabled = true
        unfocused = false
        approvals.value = listOf(first, second)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, notifications.size)
    }

    @Test fun `opening the current conversation from a notification preserves its pending approval`() = runTest {
        val approvals = ToolApprovalController()
        fake.savedConversations.value = listOf(Conversation("active", emptyList(), 0, 0))
        fake.currentConversationId.value = "active"
        val repository = object : DataRepository by fake {
            override val toolApprovals = approvals.requests
            override fun resolveToolApproval(id: String, approved: Boolean) = approvals.resolve(id, approved)
            override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?): History? {
                if (!approvals.await(ToolApprovalRequest("call", "active", "shell", "{}"))) return null
                return fake.ask(question, files, uiSubmission, activeSkillId)
            }
        }
        val vm = ChatViewModel(repository, TaskScheduler(repository, enabled = false), dispatcher)
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.ask("wait for approval")
            dispatcher.scheduler.advanceUntilIdle()
            val request = approvals.requests.value.single()
            assertTrue(vm.state.value.isLoading)

            vm.openNotificationConversation(request.conversationId)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.state.value.isLoading)
            assertEquals(listOf(request), approvals.requests.value)
            assertEquals(listOf(request), vm.state.value.toolApprovals)
            assertTrue(fake.askCalls.isEmpty())

            actions.resolveToolApproval(request.id, true)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertTrue(approvals.requests.value.isEmpty())
            assertEquals(listOf("wait for approval"), fake.askCalls.map { it.first })
            assertEquals("Test response", vm.state.value.history.last().content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `notification navigation opens another saved conversation and ignores missing targets`() = runTest {
        fake.savedConversations.value = listOf(
            Conversation("first", emptyList(), 0, 0),
            Conversation("second", listOf(Conversation.Message("answer", "assistant", "Saved answer")), 0, 0),
        )
        fake.currentConversationId.value = "first"
        val vm = ChatViewModel(fake, TaskScheduler(fake, enabled = false), dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        vm.openNotificationConversation("second")
        assertEquals("second", fake.currentConversationId.value)
        assertEquals("Saved answer", fake.chatHistory.value.single().content)

        vm.openNotificationConversation("missing")
        assertEquals("second", fake.currentConversationId.value)
        assertEquals("Saved answer", fake.chatHistory.value.single().content)
    }

    @Test fun `preview strips formatting and bounds long answers`() {
        assertEquals("A short answer", responseNotificationPreview("**A short answer**"))
        assertEquals("Response ready", responseNotificationPreview("```kotlin\nprintln(1)\n```"))
        val preview = responseNotificationPreview("long answer ".repeat(50))
        assertEquals(true, preview.length <= 181 && preview.endsWith("…"))
    }

    @Test fun `foreground at completion suppresses the alert and background completion sends it`() = runTest {
        var foreground = true
        val notifications = mutableListOf<String>()
        val repository = object : DataRepository by fake {
            override fun isLlmNotificationsEnabled() = true
        }
        val vm = ChatViewModel(
            repository,
            TaskScheduler(repository, enabled = false),
            dispatcher,
            shouldNotify = { !foreground },
            notify = { _, body, _, _ -> notifications.add(body) },
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.actions.ask("visible")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), notifications)

        fake.askGate = CompletableDeferred()
        vm.state.value.actions.ask("leave before it finishes")
        dispatcher.scheduler.advanceUntilIdle()
        foreground = false
        fake.askGate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("Test response"), notifications)

        fake.askGate = CompletableDeferred()
        vm.state.value.actions.ask("return before it finishes")
        dispatcher.scheduler.advanceUntilIdle()
        foreground = true
        fake.askGate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("Test response"), notifications)
    }

    @Test fun `completed answer notifies once but failure and disabled toggle do not`() = runTest {
        var enabled = true
        val notifications = mutableListOf<Pair<String, String>>()
        val repository = object : DataRepository by fake {
            override fun isLlmNotificationsEnabled() = enabled
        }
        val vm = ChatViewModel(
            repository,
            TaskScheduler(repository, enabled = false),
            dispatcher,
            shouldNotify = { true },
            notify = { title, body, _, _ -> notifications.add(title to body) },
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.actions.ask("hello")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("Maxit finished responding" to "Test response"), notifications)
        fake.askException = IllegalStateException("failed")
        vm.state.value.actions.ask("error")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, notifications.size)
        fake.askException = null
        enabled = false
        vm.state.value.actions.ask("disabled")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, notifications.size)
    }
}
