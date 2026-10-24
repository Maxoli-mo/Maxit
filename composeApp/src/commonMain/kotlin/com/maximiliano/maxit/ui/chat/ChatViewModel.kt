// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maximiliano.maxit.data.Conversation
import com.maximiliano.maxit.data.ConversationInputAccepted
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.Service
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.data.UiSubmission
import com.maximiliano.maxit.getBackgroundDispatcher
import com.maximiliano.maxit.network.UiError
import com.maximiliano.maxit.network.shouldShowFreeProviderSuggestions
import com.maximiliano.maxit.network.toUiError
import com.maximiliano.maxit.tools.AppPermission
import com.maximiliano.maxit.tools.PermissionController
import com.maximiliano.maxit.tools.isLocalNetworkUrl
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.conversation_untitled
import maxit.composeapp.generated.resources.error_local_network_permission
import maxit.composeapp.generated.resources.error_unsupported_file_type
import maxit.composeapp.generated.resources.litert_no_model_warning
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

class ChatViewModel(
    private val dataRepository: DataRepository,
    private val taskScheduler: TaskScheduler,
    private val backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
    private val localNetworkPermissionController: PermissionController = PermissionController(AppPermission.LOCAL_NETWORK),
) : ViewModel() {

    private val actions = ChatActions(
        ask = ::ask,
        retry = ::retry,
        toggleSpeechOutput = ::toggleSpeechOutput,
        clearHistory = ::clearHistory,
        setIsSpeaking = ::setIsSpeaking,
        addFile = ::addFile,
        removeFile = ::removeFile,
        startNewChat = { startNewChat() },
        regenerate = ::regenerate,
        cancel = ::cancel,
        selectService = ::selectService,
        loadConversation = ::loadConversation,
        deleteConversation = ::deleteConversation,
        clearUnreadHeartbeat = ::clearUnreadHeartbeat,
        clearSnackbar = ::clearSnackbar,
        undoDeleteConversation = ::undoDeleteConversation,
        submitUiCallback = { event, data -> submitUiCallback(event, data) },
        resubmit = ::resubmit,
        enterInteractiveMode = ::enterInteractiveMode,
        exitInteractiveMode = ::exitInteractiveMode,
        goBackInteractiveMode = ::goBackInteractiveMode,
        sendSmsDraft = ::sendSmsDraft,
        discardSmsDraft = ::discardSmsDraft,
        consumeComposerPrefill = ::consumeComposerPrefill,
        resolveToolApproval = dataRepository::resolveToolApproval,
        loadEarlierMessages = { _state.update { it.copy(historyLimit = it.historyLimit + 200) } },
        consumeAutomaticSpeech = ::consumeAutomaticSpeech,
    )
    private val requestGeneration = MutableStateFlow(0L)
    private val selectionGeneration = MutableStateFlow(0L)
    private val restored = CompletableDeferred<Unit>()
    private var storageReady = false
    private var currentJob: Job? = null
    private var pendingConversationDeleteJob: Job? = null
    private val _state: MutableStateFlow<ChatUiState> = MutableStateFlow(
        ChatUiState(
            actions = actions,
            showPrivacyInfo = dataRepository.isUsingSharedKey(),
        ),
    )

    init {
        updateAvailableServices()
        viewModelScope.launch {
            dataRepository.installedSkillsFlow.collect { skills ->
                _state.update { it.copy(installedSkills = skills.filter { skill -> skill.loadError == null }.toImmutableList()) }
            }
        }

        // Keep restoreCurrentConversation off the main thread; see issue #197 (large persisted
        // tool outputs caused ANRs when JSON-decoded synchronously during VM construction).
        viewModelScope.launch(backgroundDispatcher) {
            val selection = selectionGeneration.value
            try {
                dataRepository.loadConversations()
                val skipRestore = selectionGeneration.value != selection || dataRepository.pendingShareText.value != null || dataRepository.openAssistRequested.value
                if (!skipRestore) {
                    dataRepository.restoreCurrentConversation()
                    presetInteractiveModeForCurrentConversation()
                }
                storageReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUiError()) }
            } finally {
                _state.update { it.copy(isRestoring = false) }
                restored.complete(Unit)
                if (storageReady) taskScheduler.start()
            }
        }

        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.connectEnabledMcpServers()
        }
        viewModelScope.launch {
            dataRepository.fallbackStatus.collect { status ->
                _state.update { it.copy(fallbackStatus = status) }
            }
        }
        viewModelScope.launch {
            taskScheduler.executionError.collect { error -> _state.update { it.copy(backgroundError = error) } }
        }
        viewModelScope.launch {
            dataRepository.toolApprovals.collect { approvals -> _state.update { it.copy(toolApprovals = approvals.toImmutableList()) } }
        }
        taskScheduler.isLoadingCheck = { _state.value.isLoading }

        viewModelScope.launch {
            dataRepository.smsDrafts.collect { drafts ->
                _state.update { it.copy(smsDrafts = drafts.toImmutableList()) }
            }
        }

        viewModelScope.launch {
            dataRepository.openHeartbeatRequested
                .filter { it }
                .collect {
                    val heartbeatId = dataRepository.savedConversations.value
                        .firstOrNull { it.type == Conversation.TYPE_HEARTBEAT }?.id
                    if (heartbeatId != null) {
                        loadConversation(heartbeatId)
                        clearUnreadHeartbeat()
                    }
                    dataRepository.consumeOpenHeartbeatRequest()
                }
        }

        viewModelScope.launch {
            dataRepository.openAssistRequested
                .filter { it }
                .collect {
                    startNewChat()
                    dataRepository.consumeOpenAssistRequest()
                }
        }

        viewModelScope.launch {
            dataRepository.pendingShareText
                .filterNotNull()
                .collect { text ->
                    startNewChat(composerPrefill = text)
                    dataRepository.consumeOpenShareRequest()
                }
        }
    }

    val state = combine(
        _state,
        dataRepository.chatHistory,
        dataRepository.savedConversations,
        dataRepository.currentConversationId,
        dataRepository.hasUnreadHeartbeat,
    ) { state, history, conversations, conversationId, hasUnreadHeartbeat ->
        val summaries = conversations
            .sortedByDescending { it.updatedAt }
            .map {
                val isHeartbeat = it.type == Conversation.TYPE_HEARTBEAT
                val isInteractive = it.type == Conversation.TYPE_INTERACTIVE
                ConversationSummary(
                    id = it.id,
                    title = if (isHeartbeat) "" else it.title.ifEmpty { getString(Res.string.conversation_untitled) },
                    updatedAt = it.updatedAt,
                    isHeartbeat = isHeartbeat,
                    isInteractive = isInteractive,
                )
            }
        val tail = history.takeLast(state.historyLimit)
        val marker = history.lastOrNull { it.isStatusMessage && it.role == History.Role.ASSISTANT }
        val displayed = if (marker != null && tail.none { it.id == marker.id }) listOf(marker) + tail else tail
        state.copy(
            history = displayed.toImmutableList(),
            earlierMessageCount = (history.size - displayed.size).coerceAtLeast(0),
            toolApprovals = state.toolApprovals.filter { it.conversationId == conversationId }.toImmutableList(),
            supportedFileExtensions = dataRepository.supportedFileExtensions().toImmutableList(),
            savedConversations = summaries.toImmutableList(),
            currentConversationId = conversationId,
            hasUnreadHeartbeat = hasUnreadHeartbeat,
            installedSkills = dataRepository.getInstalledSkills().filter { it.loadError == null }.toImmutableList(),
        )
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = _state.value,
    )

    private fun submitUiCallback(event: String, data: Map<String, String>, beforeAsk: (suspend () -> Unit)? = null) {
        val message = if (data.isNotEmpty()) {
            val formattedData = data.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            "Responded with: $formattedData"
        } else {
            "Pressed: $event"
        }
        val lastAssistant = dataRepository.chatHistory.value.lastRenderedAssistant()
        val submission = lastAssistant?.let {
            UiSubmission(sourceContent = it.content, values = data, pressedEvent = event)
        }
        askInternal(message, submission, beforeAsk)
    }

    private fun ask(question: String?) {
        askInternal(question, null)
    }

    private fun askInternal(question: String?, uiSubmission: UiSubmission?, beforeAsk: (suspend () -> Unit)? = null, regenerate: Boolean = false) {
        if (_state.value.isLoading) return

        // Capture files before launching coroutine to avoid race with files being cleared
        val files = if (question != null) _state.value.files else persistentListOf()

        val (strippedQuestion, activeSkillId) = parseSkillInvocation(question)

        val generation = requestGeneration.updateAndGet { it + 1 }
        val previousJob = currentJob
        // Claim loading before launch, so two UI submissions cannot both enter.
        _state.update { it.copy(isLoading = true, isStopping = false, error = null, showFreeProviderSuggestions = false, automaticSpeech = null) }
        var completedAnswer: History? = null
        val job = viewModelScope.launch(backgroundDispatcher) {
            try {
                previousJob?.join()
                restored.await()
                check(storageReady) { "Conversation history could not be loaded. Resolve the storage error before starting another run." }
                if (requestGeneration.value != generation) return@launch
                beforeAsk?.invoke()
                // Android 17+ blocks LAN traffic without the local network permission — without
                // asking first, requests to self-hosted servers silently never leave the device.
                if (!ensureLocalNetworkPermission()) {
                    _state.update {
                        it.copy(
                            error = UiError.Resource(Res.string.error_local_network_permission),
                            showFreeProviderSuggestions = false,
                        )
                    }
                    return@launch
                }
                completedAnswer = withContext(
                    ConversationInputAccepted {
                        _state.update { state ->
                            if (requestGeneration.value != generation) state else state.copy(acceptedInputId = generation, files = state.files.filterNot { file -> file in files }.toImmutableList())
                        }
                    },
                ) {
                    if (regenerate) dataRepository.regenerate() else dataRepository.ask(strippedQuestion, files, uiSubmission, activeSkillId)
                }
            } catch (exception: Exception) {
                // CancellationException must be re-thrown to properly propagate coroutine cancellation
                if (exception is CancellationException) throw exception

                val showUpsell = shouldShowFreeProviderSuggestions(
                    noConfiguredServices = dataRepository.getConfiguredServiceInstances().isEmpty(),
                    exception = exception,
                )
                if (requestGeneration.value == generation) _state.update { it.copy(error = exception.toUiError(), showFreeProviderSuggestions = showUpsell) }
            }
        }
        currentJob = job
        // Completion also runs when cancellation prevents dispatch, after all owned cleanup.
        job.invokeOnCompletion { cause ->
            _state.update { state ->
                if (requestGeneration.value != generation) {
                    state
                } else {
                    state.copy(
                        isLoading = false,
                        isStopping = false,
                        settledInputId = generation,
                        automaticSpeech = completedAnswer.takeIf { cause == null && state.isSpeechOutputEnabled && !state.isStopping },
                    )
                }
            }
        }
    }

    /**
     * True unless the active service points at a local network host and the user
     * denied the local network permission. Cheap no-op on non-Android platforms.
     */
    private suspend fun ensureLocalNetworkPermission(): Boolean {
        val instance = dataRepository.getConfiguredServiceInstances().firstOrNull() ?: return true
        val baseUrl = dataRepository.getInstanceBaseUrl(instance.instanceId, Service.fromId(instance.serviceId))
        if (!isLocalNetworkUrl(baseUrl)) return true
        return localNetworkPermissionController.requestPermission()
    }

    private fun clearHistory() = startNewChat()

    /**
     * If [text] begins with `/<skill-id>`, look up the skill among the currently-
     * readable installed skills and return its id alongside the verbatim user
     * text. The text is sent unchanged so the conversation visibly reflects what
     * the user typed; the skill's instructions in the system prompt tell the model
     * how to parse the args after the slash command. Falls through with null skill
     * id when no match — slash commands are opt-in.
     */
    private fun parseSkillInvocation(text: String?): Pair<String?, String?> {
        if (text == null) return null to null
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('/')) return text to null
        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        val rawId = if (firstSpace < 0) trimmed.substring(1) else trimmed.substring(1, firstSpace)
        if (rawId.isEmpty()) return text to null
        val skill = dataRepository.getInstalledSkills().firstOrNull { it.loadError == null && it.id.equals(rawId, ignoreCase = true) }
            ?: return text to null
        return text to skill.id
    }

    private fun setIsSpeaking(isSpeaking: Boolean, contentId: String) {
        _state.update {
            it.copy(
                isSpeaking = isSpeaking,
                isSpeakingContentId = if (isSpeaking) {
                    contentId
                } else {
                    it.isSpeakingContentId
                },
            )
        }
    }

    private fun addFile(file: PlatformFile) {
        val ext = file.extension.lowercase()
        val supported = dataRepository.supportedFileExtensions()
        if (ext.isEmpty() || ext !in supported) {
            _state.update {
                it.copy(snackbarMessage = Res.string.error_unsupported_file_type)
            }
            return
        }
        _state.update {
            it.copy(files = (it.files + file).toImmutableList())
        }
    }

    private fun removeFile(file: PlatformFile) {
        _state.update {
            it.copy(files = it.files.filterNot { f -> f == file }.toImmutableList())
        }
    }

    private fun clearSnackbar() {
        _state.update {
            it.copy(snackbarMessage = null)
        }
    }

    private fun retry() {
        ask(null)
    }

    private fun consumeAutomaticSpeech(answerId: String): Boolean {
        while (true) {
            val state = _state.value
            if (state.automaticSpeech?.id != answerId) return false
            if (_state.compareAndSet(state, state.copy(automaticSpeech = null))) return true
        }
    }

    private fun toggleSpeechOutput() {
        _state.update {
            it.copy(
                isSpeechOutputEnabled = !it.isSpeechOutputEnabled,
                automaticSpeech = null,
            )
        }
    }

    private fun cancel() {
        if (currentJob?.isActive == true) {
            _state.update { it.copy(isStopping = true) }
            currentJob?.cancel()
        }
    }

    private fun selectService(instanceId: String) {
        dataRepository.setFreeServicePrimary(false)
        val instances = dataRepository.getConfiguredServiceInstances()
        val currentIds = instances.map { it.instanceId }
        if (instanceId !in currentIds) return
        val reordered = listOf(instanceId) + currentIds.filter { it != instanceId }
        dataRepository.reorderConfiguredServices(reordered)
        updateAvailableServices()
    }

    private fun updateAvailableServices() {
        val entries = dataRepository.getServiceEntries().toImmutableList()

        val primaryService = entries.firstOrNull()?.let { Service.fromId(it.serviceId) }
        val warning = if (primaryService?.isOnDevice == true && dataRepository.getLocalDownloadedModels().isEmpty()) {
            Res.string.litert_no_model_warning
        } else {
            null
        }
        _state.update { it.copy(availableServices = entries, warning = warning, showPrivacyInfo = dataRepository.isUsingSharedKey()) }
    }

    private fun regenerate() {
        askInternal(null, null, regenerate = true)
    }

    private fun loadConversation(id: String) {
        selectionGeneration.updateAndGet { it + 1 }
        requestGeneration.update { it + 1 }
        currentJob?.cancel()
        dataRepository.setInteractiveMode(false)
        dataRepository.loadConversation(id)
        _state.update {
            it.copy(
                error = null,
                showFreeProviderSuggestions = false,
                isInteractiveMode = false,
                isLoading = false,
                isStopping = false,
                settledInputId = requestGeneration.value,
                composerPrefill = null,
                automaticSpeech = null,
            )
        }
    }

    private fun deleteConversation(id: String) {
        commitPendingConversationDeletion()
        _state.update { it.copy(pendingConversationDeletion = id) }
        pendingConversationDeleteJob = viewModelScope.launch(backgroundDispatcher) {
            delay(4.seconds)
            dataRepository.deleteConversation(id)
            _state.update { it.copy(pendingConversationDeletion = null) }
        }
    }

    private fun undoDeleteConversation() {
        pendingConversationDeleteJob?.cancel()
        pendingConversationDeleteJob = null
        _state.update { it.copy(pendingConversationDeletion = null) }
    }

    private fun commitPendingConversationDeletion() {
        pendingConversationDeleteJob?.cancel()
        pendingConversationDeleteJob = null
        val pendingId = _state.value.pendingConversationDeletion ?: return
        _state.update { it.copy(pendingConversationDeletion = null) }
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.deleteConversation(pendingId)
        }
    }

    override fun onCleared() {
        commitPendingConversationDeletion()
        // The scheduler lives longer than this ViewModel (it's a singleton driving the
        // Android foreground service). Reset the predicate so the daemon path keeps
        // running without a stale reference to a dead state flow. The foreground-visible
        // signal (`appInForeground`) is tracked separately via `ProcessLifecycleOwner`
        // on Android — ViewModel lifecycle is too narrow (survives backgrounding).
        taskScheduler.isLoadingCheck = { false }
        super.onCleared()
    }

    private fun clearUnreadHeartbeat() {
        dataRepository.clearUnreadHeartbeat()
    }

    private fun sendSmsDraft(draftId: String) {
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.sendSmsDraft(draftId)
        }
    }

    private fun discardSmsDraft(draftId: String) {
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.discardSmsDraft(draftId)
        }
    }

    private fun startNewChat(composerPrefill: String? = null) {
        selectionGeneration.updateAndGet { it + 1 }
        requestGeneration.update { it + 1 }
        currentJob?.cancel()
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(false)
        _state.update {
            it.copy(
                error = null,
                showFreeProviderSuggestions = false,
                isInteractiveMode = false,
                isLoading = false,
                isStopping = false,
                settledInputId = requestGeneration.value,
                composerPrefill = composerPrefill,
                automaticSpeech = null,
            )
        }
    }

    private fun consumeComposerPrefill() {
        _state.update { it.copy(composerPrefill = null) }
    }

    private fun enterInteractiveMode() {
        // Parked for a future skill; keep existing history intact. See docs/skills/interactive-ui/SKILL.md.
    }

    private fun exitInteractiveMode() {
        requestGeneration.update { it + 1 }
        currentJob?.cancel()
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(false)
        _state.update {
            it.copy(
                isInteractiveMode = false,
                isLoading = false,
                isStopping = false,
                settledInputId = requestGeneration.value,
                automaticSpeech = null,
                error = null,
                showFreeProviderSuggestions = false,
            )
        }
    }

    private fun resubmit(messageId: String, event: String, data: Map<String, String>) {
        if (_state.value.isLoading) return
        submitUiCallback(event, data) { dataRepository.truncateFrom(messageId) }
    }

    private fun goBackInteractiveMode() {
        if (_state.value.isLoading) return
        val userCount = dataRepository.chatHistory.value.count { it.role == History.Role.USER && !it.superseded }
        if (userCount <= 1) {
            // Go back to initial prompt — clear history but stay in interactive mode
            dataRepository.clearHistory()
        } else {
            val generation = requestGeneration.value
            viewModelScope.launch(backgroundDispatcher) {
                if (requestGeneration.value != generation) return@launch
                try {
                    dataRepository.popLastExchange()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (requestGeneration.value == generation) _state.update { it.copy(error = e.toUiError()) }
                }
            }
        }
    }

    fun refreshSettings() {
        updateAvailableServices()
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.restoreCurrentConversation()
            presetInteractiveModeForCurrentConversation()
        }
    }

    private fun presetInteractiveModeForCurrentConversation() {
        // Legacy chats open normally while parked; see docs/skills/interactive-ui/SKILL.md.
        dataRepository.setInteractiveMode(false)
        _state.update { it.copy(isInteractiveMode = false) }
    }
}
