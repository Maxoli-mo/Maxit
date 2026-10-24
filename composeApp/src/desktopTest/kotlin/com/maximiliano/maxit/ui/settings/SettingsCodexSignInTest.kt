package com.maximiliano.maxit.ui.settings

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.maximiliano.maxit.DaemonController
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.ImportSection
import com.maximiliano.maxit.data.Service
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.network.CodexDeviceAuthorization
import com.maximiliano.maxit.testutil.FakeDataRepository
import com.maximiliano.maxit.tools.AppPermission
import com.maximiliano.maxit.tools.PermissionController
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCodexSignInTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val daemon = object : DaemonController {
        override fun start() = Unit
        override fun stop() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: CodexRepository) = SettingsViewModel(
        repository,
        daemon,
        PermissionController(AppPermission.POST_NOTIFICATIONS),
        TaskScheduler(repository, enabled = false),
        dispatcher,
    ).also { store.put("settings", it) }

    @Test
    fun `adding and showing Codex does not request sign in or expose an api key`() = runTest {
        val repository = CodexRepository()
        repository.base.setInstanceApiKey(Service.OpenAICodex.id, "not-for-the-ui")
        val viewModel = viewModel(repository)
        viewModel.state.test {
            assertEquals("", awaitItem().configuredServices.single().apiKey)
            viewModel.onScreenVisible()
            dispatcher.scheduler.runCurrent()
            assertEquals(0, repository.beginCalls)
            assertEquals(0, repository.validationCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel stops requesting a code and permits another attempt`() = runTest {
        val repository = CodexRepository()
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(CodexAuthStatus.RequestingCode, expectMostRecentItem().configuredServices.single().codexAuth.status)
            viewModel.actions.onCancelCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, repository.cancelledRequests)
            assertEquals(CodexAuthStatus.SignedOut, expectMostRecentItem().configuredServices.single().codexAuth.status)
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(2, repository.beginCalls)
            viewModel.actions.onCancelCodexSignIn(Service.OpenAICodex.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `device login publishes only the code then refreshes status and models`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = CodexRepository().apply {
            begin = { CodexDeviceAuthorization("ABCD-EFGH", Clock.System.now().toEpochMilliseconds() + 60_000) }
            complete = { gate.await() }
            validate = {
                base.setInstanceModels(Service.OpenAICodex.id, listOf(SettingsModel("codex-new", "", isSelected = true)))
            }
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            val waiting = expectMostRecentItem().configuredServices.single().codexAuth
            assertEquals(CodexAuthStatus.Waiting, waiting.status)
            assertEquals("ABCD-EFGH", waiting.userCode)
            assertEquals("https://auth.openai.com/codex/device", waiting.verificationUri)
            gate.complete(Unit)
            dispatcher.scheduler.runCurrent()
            val entry = expectMostRecentItem().configuredServices.single()
            assertEquals(CodexAuthStatus.SignedIn, entry.codexAuth.status)
            assertNull(entry.codexAuth.userCode)
            assertNull(entry.codexAuth.verificationUri)
            assertEquals(ConnectionStatus.Connected, entry.connectionStatus)
            assertEquals("codex-new", entry.selectedModel?.id)
            assertEquals(1, repository.validationCalls)
            viewModel.actions.onSelectModel(Service.OpenAICodex.id, "future-model-id")
            dispatcher.scheduler.runCurrent()
            assertEquals("future-model-id", expectMostRecentItem().configuredServices.single().selectedModel?.id)
        }
    }

    @Test
    fun `cancel while waiting clears the code and stops polling`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = CodexRepository().apply {
            begin = { CodexDeviceAuthorization("ABCD-EFGH", Clock.System.now().toEpochMilliseconds() + 60_000) }
            complete = { gate.await() }
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(CodexAuthStatus.Waiting, expectMostRecentItem().configuredServices.single().codexAuth.status)
            viewModel.actions.onCancelCodexSignIn(Service.OpenAICodex.id)
            gate.complete(Unit)
            dispatcher.scheduler.runCurrent()
            val auth = expectMostRecentItem().configuredServices.single().codexAuth
            assertEquals(CodexAuthStatus.SignedOut, auth.status)
            assertNull(auth.userCode)
            assertFalse(repository.signedIn)
            assertEquals(0, repository.validationCalls)
        }
    }

    @Test
    fun `merging unrelated settings cancels hidden sign in and permits another attempt`() = runTest {
        var cancelledPolls = 0
        val repository = CodexRepository().apply {
            begin = { CodexDeviceAuthorization("ABCD-EFGH", Clock.System.now().toEpochMilliseconds() + 60_000) }
            complete = {
                try {
                    awaitCancellation()
                } finally {
                    cancelledPolls++
                }
            }
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(CodexAuthStatus.Waiting, expectMostRecentItem().configuredServices.single().codexAuth.status)

            assertEquals(ImportResult.Success, viewModel.actions.onImportSettings("{}".encodeToByteArray(), setOf(ImportSection.SOUL), false))
            dispatcher.scheduler.runCurrent()
            assertEquals(1, cancelledPolls)
            val auth = expectMostRecentItem().configuredServices.single().codexAuth
            assertEquals(CodexAuthStatus.SignedOut, auth.status)
            assertNull(auth.userCode)
            assertFalse(repository.signedIn)

            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(2, repository.beginCalls)
            assertEquals(CodexAuthStatus.Waiting, expectMostRecentItem().configuredServices.single().codexAuth.status)
            viewModel.actions.onCancelCodexSignIn(Service.OpenAICodex.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `expired code is cleared without polling`() = runTest {
        val repository = CodexRepository().apply {
            begin = { CodexDeviceAuthorization("OLD-CODE", Clock.System.now().toEpochMilliseconds() - 1) }
            complete = { error("Expired codes must not be polled") }
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            val auth = expectMostRecentItem().configuredServices.single().codexAuth
            assertEquals(CodexAuthError.Expired, auth.error)
            assertNull(auth.userCode)
        }
    }

    @Test
    fun `cancelling one instance does not cancel another instance`() = runTest {
        val repository = CodexRepository().apply {
            base.setConfiguredServices(Service.OpenAICodex, Service.OpenAICodex)
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            val ids = awaitItem().configuredServices.map { it.instanceId }
            ids.forEach(viewModel.actions.onBeginCodexSignIn)
            dispatcher.scheduler.runCurrent()
            viewModel.actions.onCancelCodexSignIn(ids.first())
            dispatcher.scheduler.runCurrent()
            val entries = expectMostRecentItem().configuredServices
            assertEquals(CodexAuthStatus.SignedOut, entries.first().codexAuth.status)
            assertEquals(CodexAuthStatus.RequestingCode, entries.last().codexAuth.status)
            assertEquals(2, repository.beginCalls)
            assertEquals(1, repository.cancelledRequests)
            viewModel.actions.onCancelCodexSignIn(ids.last())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing the viewmodel cancels the pending sign in`() = runTest {
        val repository = CodexRepository()
        val viewModel = viewModel(repository)
        viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
        dispatcher.scheduler.runCurrent()
        store.clear()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, repository.cancelledRequests)
    }

    @Test
    fun `request errors use a safe public error instead of exception text`() = runTest {
        val repository = CodexRepository().apply {
            begin = { error("access_token=secret") }
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            val auth = expectMostRecentItem().configuredServices.single().codexAuth
            assertEquals(CodexAuthError.RequestFailed, auth.error)
            assertFalse(auth.toString().contains("secret"))
        }
    }

    @Test
    fun `sign out cancels model reload and ignores its late result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = CodexRepository().apply {
            signedIn = true
            validate = { withContext(NonCancellable) { gate.await() } }
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.onScreenVisible()
            dispatcher.scheduler.runCurrent()
            assertEquals(ConnectionStatus.Checking, expectMostRecentItem().configuredServices.single().connectionStatus)
            viewModel.actions.onSignOutCodex(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            gate.complete(Unit)
            dispatcher.scheduler.runCurrent()
            val entry = expectMostRecentItem().configuredServices.single()
            assertEquals(1, repository.signOutCalls)
            assertEquals(CodexAuthStatus.SignedOut, entry.codexAuth.status)
            assertEquals(ConnectionStatus.Unknown, entry.connectionStatus)
            assertFalse(repository.signedIn)
        }
    }

    @Test
    fun `removal cancels sign in immediately even before undo timeout`() = runTest {
        val repository = CodexRepository()
        val viewModel = viewModel(repository)
        viewModel.state.test {
            awaitItem()
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            viewModel.actions.onRemoveService(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            val state = expectMostRecentItem()
            assertEquals(CodexAuthStatus.SignedOut, state.configuredServices.single().codexAuth.status)
            viewModel.actions.onBeginCodexSignIn(Service.OpenAICodex.id)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, repository.beginCalls)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, expectMostRecentItem().configuredServices.size)
        }
    }

    @Test
    fun `optional custom model preserves the list choice and remembers the typed id`() = runTest {
        val repository = CodexRepository().apply {
            signedIn = true
            base.setInstanceModels(Service.OpenAICodex.id, listOf(SettingsModel("gpt-6-luna", "", isSelected = true)))
        }
        val viewModel = viewModel(repository)
        viewModel.state.test {
            assertFalse(awaitItem().configuredServices.single().useCustomModel)
            viewModel.actions.onToggleUseCustomModel(Service.OpenAICodex.id, true)
            viewModel.actions.onChangeCustomModelId(Service.OpenAICodex.id, "future-model")
            dispatcher.scheduler.runCurrent()
            val custom = expectMostRecentItem().configuredServices.single()
            assertTrue(custom.useCustomModel)
            assertEquals("future-model", custom.customModelId)
            assertEquals("gpt-6-luna", custom.selectedModel?.id)
            viewModel.actions.onToggleUseCustomModel(Service.OpenAICodex.id, false)
            dispatcher.scheduler.runCurrent()
            val listed = expectMostRecentItem().configuredServices.single()
            assertFalse(listed.useCustomModel)
            assertEquals("future-model", listed.customModelId)
            assertEquals("gpt-6-luna", listed.selectedModel?.id)
        }
    }

    private class CodexRepository(val base: FakeDataRepository = FakeDataRepository()) : DataRepository by base {
        init {
            base.setConfiguredServices(Service.OpenAICodex)
        }

        var signedIn = false
        var beginCalls = 0
        var cancelledRequests = 0
        var validationCalls = 0
        var signOutCalls = 0
        var begin: suspend () -> CodexDeviceAuthorization = {
            try {
                awaitCancellation()
            } finally {
                cancelledRequests++
            }
        }
        var complete: suspend () -> Unit = { awaitCancellation() }
        var validate: suspend () -> Unit = {}

        override fun isCodexSignedIn(instanceId: String) = signedIn

        override suspend fun beginCodexSignIn(instanceId: String): CodexDeviceAuthorization {
            beginCalls++
            return begin()
        }

        override suspend fun completeCodexSignIn(instanceId: String, authorization: CodexDeviceAuthorization) {
            complete()
            signedIn = true
        }

        override fun signOutCodex(instanceId: String) {
            signOutCalls++
            signedIn = false
        }

        override suspend fun validateConnection(service: Service, instanceId: String) {
            validationCalls++
            validate()
        }
    }
}
