package com.maximiliano.maxit.ui.chat

import app.cash.turbine.test
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.MessageState
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.data.UiSubmission
import com.maximiliano.maxit.testutil.FakeDataRepository
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCompletionTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeDataRepository()

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: DataRepository = fake) = ChatViewModel(repository, TaskScheduler(repository, enabled = false), dispatcher)

    @Test
    fun `stop before dispatch settles owned generation and permits a second submission`() = runTest {
        val vm = viewModel()
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.ask("never dispatched")
            actions.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.isStopping)
            assertTrue(fake.askCalls.isEmpty())
            assertTrue(vm.state.value.settledInputId > 0)

            actions.ask("second")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("second"), fake.askCalls.map { it.first })
            assertFalse(vm.state.value.isLoading)
            assertTrue(vm.state.value.acceptedInputId > 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stop remains busy until cancellation cleanup actually finishes`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val repository = object : DataRepository by fake {
            override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?): History? {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        val vm = viewModel(repository)
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.ask("first")
            dispatcher.scheduler.advanceUntilIdle()
            actions.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.state.value.isLoading)
            assertTrue(vm.state.value.isStopping)
            cleanup.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.isStopping)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `older cleanup cannot settle a newer generation`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : DataRepository by fake {
            override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?): History? {
                if (++calls == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                } else {
                    second.await()
                    return fake.ask(question, files, uiSubmission, activeSkillId)
                }
            }
        }
        val vm = viewModel(repository)
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.ask("first")
            dispatcher.scheduler.advanceUntilIdle()
            actions.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.state.value.isStopping)
            actions.startNewChat()
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isStopping)
            actions.ask("second")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, calls)
            cleanup.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(2, calls)
            assertTrue(vm.state.value.isLoading)
            second.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `only a successful new answer is offered once across consumers and failed stopped runs`() = runTest {
        val vm = viewModel()
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.toggleSpeechOutput()
            actions.ask("A")
            dispatcher.scheduler.advanceUntilIdle()
            val answerA = assertNotNull(vm.state.value.automaticSpeech)
            assertTrue(actions.consumeAutomaticSpeech(answerA.id))
            assertFalse(actions.consumeAutomaticSpeech(answerA.id))
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.state.value.automaticSpeech)

            fake.askException = IllegalStateException("preacceptance failure")
            actions.ask("B fails")
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.state.value.automaticSpeech)
            fake.askException = null
            fake.askGate = CompletableDeferred()
            actions.ask("B stops")
            dispatcher.scheduler.advanceUntilIdle()
            actions.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.state.value.automaticSpeech)
            fake.askGate = null

            actions.ask("B succeeds")
            dispatcher.scheduler.advanceUntilIdle()
            val answerB = assertNotNull(vm.state.value.automaticSpeech)
            assertTrue(answerB.id != answerA.id)
            assertTrue(actions.consumeAutomaticSpeech(answerB.id))
            assertFalse(actions.consumeAutomaticSpeech(answerB.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `speech uses the owned return value even if a different run publishes another answer`() = runTest {
        val own = History(role = History.Role.ASSISTANT, content = "Owned answer", runId = "owned-run")
        val unrelated = History(role = History.Role.ASSISTANT, content = "Other run", runId = "other-run")
        val repository = object : DataRepository by fake {
            override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?): History? {
                fake.chatHistory.value = listOf(unrelated)
                return own
            }
        }
        val vm = viewModel(repository)
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.toggleSpeechOutput()
            actions.ask("Question")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(unrelated), vm.state.value.history)
            assertEquals(own, vm.state.value.automaticSpeech)
            assertTrue(actions.consumeAutomaticSpeech(own.id))
            assertFalse(actions.consumeAutomaticSpeech(own.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paused requests returning no answer do not speak prior or partial text`() = runTest {
        fake.chatHistory.value = listOf(History(role = History.Role.ASSISTANT, content = "Earlier answer"))
        val repository = object : DataRepository by fake {
            override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?): History? {
                fake.chatHistory.value += History(role = History.Role.ASSISTANT, content = "Partial", state = MessageState.PAUSED)
                return null
            }
        }
        val vm = viewModel(repository)
        vm.state.test {
            val actions = awaitItem().actions
            dispatcher.scheduler.advanceUntilIdle()
            actions.toggleSpeechOutput()
            actions.ask("Paused question")
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.automaticSpeech)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
