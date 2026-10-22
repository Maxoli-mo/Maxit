// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui.chat

import app.cash.turbine.test
import com.maximiliano.maxit.data.Conversation
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.Service
import com.maximiliano.maxit.data.ServiceEntry
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.testutil.FakeDataRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelExtendedTest {

    private val testDispatcher = StandardTestDispatcher()
    private val unconfinedDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepository: FakeDataRepository
    private val legacyConversation = Conversation(
        id = "legacy-interactive",
        messages = listOf(
            Conversation.Message(id = "user", role = "user", content = "Show a screen"),
            Conversation.Message(
                id = "assistant",
                role = "assistant",
                content = "Saved reply\n```kai-ui\n{\"type\":\"text\",\"value\":\"Saved screen\"}\n```",
            ),
        ),
        createdAt = 1L,
        updatedAt = 2L,
        title = "Saved interactive chat",
        type = Conversation.TYPE_INTERACTIVE,
    )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDataRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(repository: DataRepository = fakeRepository): ChatViewModel {
        val noOpScheduler = TaskScheduler(repository, enabled = false)
        return ChatViewModel(repository, noOpScheduler, unconfinedDispatcher)
    }

    private fun makeServiceEntry(instanceId: String, service: Service) = ServiceEntry(
        instanceId = instanceId,
        serviceId = service.id,
        serviceName = service.displayName,
        modelId = "test-model",
        icon = service.icon,
    )

    // ---- Concurrent ask prevention ----

    @Test
    fun `concurrent ask is ignored while a previous ask is still loading`() = runTest {
        // Gate the first ask so it stays in flight
        val gate = CompletableDeferred<Unit>()
        fakeRepository.askGate = gate
        val viewModel = createViewModel()

        viewModel.state.test {
            val initialState = awaitItem()
            initialState.actions.ask("first")
            // First ask is now suspended at the gate, isLoading should be true
            var loadingState: ChatUiState
            do {
                loadingState = awaitItem()
            } while (!loadingState.isLoading)
            assertTrue(loadingState.isLoading)
            assertEquals(1, fakeRepository.askCalls.size)

            // Second ask should be ignored entirely while loading
            loadingState.actions.ask("second")
            testDispatcher.scheduler.advanceUntilIdle()
            // No new ask call recorded
            assertEquals(1, fakeRepository.askCalls.size)

            // Release the gate so the first ask completes
            fakeRepository.askGate = null
            gate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- selectService ----

    @Test
    fun `unconfigured chat does not offer legacy free proxy modes`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.availableServices.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectService reorders configured services so the selected instance is first`() = runTest {
        fakeRepository.setConfiguredServices(Service.Gemini, Service.OpenAI, Service.Anthropic)
        fakeRepository.fakeServiceEntries = listOf(
            makeServiceEntry("gemini", Service.Gemini),
            makeServiceEntry("openai", Service.OpenAI),
            makeServiceEntry("anthropic", Service.Anthropic),
        )

        val viewModel = createViewModel()
        viewModel.state.test {
            skipItems(1)
            val initialState = awaitItem()
            assertEquals("gemini", initialState.availableServices.first().instanceId)
            assertEquals(3, initialState.availableServices.size)
            assertTrue(initialState.availableServices.none { it.serviceId == Service.Free.id })

            // Update fakeServiceEntries to reflect the reorder happens via getServiceEntries
            // The fake's reorderConfiguredServices changes configuredInstances, but
            // fakeServiceEntries is independent — update it after reorder happens.
            fakeRepository.fakeServiceEntries = listOf(
                makeServiceEntry("openai", Service.OpenAI),
                makeServiceEntry("gemini", Service.Gemini),
                makeServiceEntry("anthropic", Service.Anthropic),
            )

            initialState.actions.selectService("openai")
            testDispatcher.scheduler.advanceUntilIdle()

            val reordered = awaitItem()
            assertEquals("openai", reordered.availableServices.first().instanceId)
            // Repository should have been told to reorder
            val configured = fakeRepository.getConfiguredServiceInstances()
            assertEquals("openai", configured.first().instanceId)
        }
    }

    @Test
    fun `selectService is a no-op when instanceId is unknown`() = runTest {
        fakeRepository.setConfiguredServices(Service.Gemini, Service.OpenAI)
        fakeRepository.fakeServiceEntries = listOf(
            makeServiceEntry("gemini", Service.Gemini),
            makeServiceEntry("openai", Service.OpenAI),
        )
        val viewModel = createViewModel()

        viewModel.state.test {
            skipItems(1)
            val initialState = awaitItem()
            initialState.actions.selectService("nonexistent_id")
            testDispatcher.scheduler.advanceUntilIdle()

            // Order should be unchanged
            val configured = fakeRepository.getConfiguredServiceInstances()
            assertEquals("gemini", configured[0].instanceId)
            assertEquals("openai", configured[1].instanceId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- startNewChat ----

    @Test
    fun `startNewChat clears history error and isLoading`() = runTest {
        // Pre-populate some history
        fakeRepository.chatHistory.value = listOf(
            History(role = History.Role.USER, content = "Hi"),
            History(role = History.Role.ASSISTANT, content = "Hello"),
        )
        val viewModel = createViewModel()

        viewModel.state.test {
            // Wait for initial state to surface the pre-populated history
            var stateWithHistory: ChatUiState
            do {
                stateWithHistory = awaitItem()
            } while (stateWithHistory.history.isEmpty())
            assertEquals(2, stateWithHistory.history.size)

            stateWithHistory.actions.startNewChat()
            testDispatcher.scheduler.advanceUntilIdle()

            var clearedState: ChatUiState
            do {
                clearedState = awaitItem()
            } while (clearedState.history.isNotEmpty())
            assertTrue(clearedState.history.isEmpty())
            assertNull(clearedState.error)
            assertFalse(clearedState.isLoading)
            assertFalse(clearedState.isInteractiveMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- regenerate ----

    @Test
    fun `regenerate truncates to last user message and re-asks with null`() = runTest {
        fakeRepository.chatHistory.value = listOf(
            History(role = History.Role.USER, content = "First"),
            History(role = History.Role.ASSISTANT, content = "Old answer"),
        )
        val viewModel = createViewModel()

        viewModel.state.test {
            // Wait for initial state to surface
            var initialState: ChatUiState
            do {
                initialState = awaitItem()
            } while (initialState.history.size < 2)

            initialState.actions.regenerate()
            testDispatcher.scheduler.advanceUntilIdle()

            // Repository.regenerate must have been called
            assertEquals(1, fakeRepository.regenerateCalls)
            // ask was invoked again with a null question (retry semantics)
            assertTrue(fakeRepository.askCalls.any { it.first == null })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- Parked interactive UI ----

    @Test
    fun `enterInteractiveMode is a no-op without losing the current chat`() = runTest {
        fakeRepository.savedConversations.value = listOf(legacyConversation)
        fakeRepository.loadConversation(legacyConversation.id)
        val history = fakeRepository.chatHistory.value
        val viewModel = createViewModel()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val initialState = expectMostRecentItem()

            initialState.actions.enterInteractiveMode()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(initialState, viewModel.state.value)
            assertEquals(history, fakeRepository.chatHistory.value)
            assertEquals(legacyConversation.id, fakeRepository.currentConversationId.value)
            assertEquals(listOf(legacyConversation), fakeRepository.savedConversations.value)
            assertFalse(viewModel.state.value.isInteractiveMode)
            assertFalse(fakeRepository.isInteractiveModeActive())
            assertTrue(fakeRepository.askCalls.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening an old interactive chat preserves content and never retries plain replies`() = runTest {
        fakeRepository.savedConversations.value = listOf(legacyConversation)
        val viewModel = createViewModel()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val initial = expectMostRecentItem()
            fakeRepository.setInteractiveMode(true)
            initial.actions.loadConversation(legacyConversation.id)
            testDispatcher.scheduler.advanceUntilIdle()

            val loaded = viewModel.state.value
            assertFalse(loaded.isInteractiveMode)
            assertFalse(fakeRepository.isInteractiveModeActive())
            assertEquals(legacyConversation.id, loaded.currentConversationId)
            assertEquals(legacyConversation.messages.map { it.content }, loaded.history.map { it.content })
            assertEquals(listOf(legacyConversation), fakeRepository.savedConversations.value)

            loaded.actions.ask("Continue in chat")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("Continue in chat"), fakeRepository.askCalls.map { it.first })
            assertEquals("Test response", viewModel.state.value.history.last().content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startup and settings refresh restore old interactive chats as normal chat`() = runTest {
        fakeRepository.savedConversations.value = listOf(legacyConversation)
        fakeRepository.setInteractiveMode(true)
        val repository = object : DataRepository by fakeRepository {
            override fun restoreCurrentConversation() {
                fakeRepository.restoreCurrentConversation()
                fakeRepository.loadConversation(legacyConversation.id)
            }
        }
        val viewModel = createViewModel(repository)

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val restored = expectMostRecentItem()
            assertFalse(restored.isRestoring)
            assertFalse(restored.isInteractiveMode)
            assertFalse(fakeRepository.isInteractiveModeActive())
            assertEquals(legacyConversation.id, restored.currentConversationId)
            assertEquals(legacyConversation.messages.map { it.content }, restored.history.map { it.content })
            assertEquals(1, fakeRepository.restoreCurrentConversationCalls)

            fakeRepository.setInteractiveMode(true)
            viewModel.refreshSettings()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(restored, viewModel.state.value)
            assertFalse(fakeRepository.isInteractiveModeActive())
            assertEquals(2, fakeRepository.restoreCurrentConversationCalls)
            assertEquals(listOf(legacyConversation), fakeRepository.savedConversations.value)

            restored.actions.ask("Continue after restore")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("Continue after restore"), fakeRepository.askCalls.map { it.first })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stale persisted interactive flag cannot enable mode in an empty chat`() = runTest {
        fakeRepository.setInteractiveMode(true)
        val viewModel = createViewModel()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.isInteractiveMode)
            assertFalse(fakeRepository.isInteractiveModeActive())
            assertNull(state.currentConversationId)
            assertTrue(state.history.isEmpty())

            state.actions.ask("Hello")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("Hello"), fakeRepository.askCalls.map { it.first })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- showPrivacyInfo ----

    @Test
    fun `showPrivacyInfo is false when current service is not Free`() = runTest {
        fakeRepository.setCurrentService(Service.Gemini)
        val viewModel = createViewModel()
        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.showPrivacyInfo)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- files ----

    @Test
    fun `files list defaults to empty`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.files.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- clearSnackbar ----

    @Test
    fun `clearSnackbar clears the snackbar message`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val initialState = awaitItem()
            assertNull(initialState.snackbarMessage)
            // Calling clearSnackbar when there's nothing to clear should be safe
            initialState.actions.clearSnackbar()
            testDispatcher.scheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- cancel ----

    @Test
    fun `cancel stops an in-flight ask and resets isLoading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeRepository.askGate = gate
        val viewModel = createViewModel()

        viewModel.state.test {
            val initialState = awaitItem()
            initialState.actions.ask("hello")

            var loadingState: ChatUiState
            do {
                loadingState = awaitItem()
            } while (!loadingState.isLoading)
            assertTrue(loadingState.isLoading)

            loadingState.actions.cancel()
            testDispatcher.scheduler.advanceUntilIdle()

            var cancelledState: ChatUiState
            do {
                cancelledState = awaitItem()
            } while (cancelledState.isLoading)
            assertFalse(cancelledState.isLoading)
            // Release gate so the cancelled coroutine can finish unwinding
            gate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
