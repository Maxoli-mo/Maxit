// Modified for Maxit, 2026-10-20.

@file:OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class, ExperimentalUuidApi::class)

package com.maximiliano.maxit.data

import com.maximiliano.maxit.Platform
import com.maximiliano.maxit.SandboxController
import com.maximiliano.maxit.compressImageBytes
import com.maximiliano.maxit.currentPlatform
import com.maximiliano.maxit.data.providers.buildAnthropicMessages
import com.maximiliano.maxit.data.providers.buildOpenAIMessages
import com.maximiliano.maxit.data.providers.toResponsesInput
import com.maximiliano.maxit.email.EmailPoller
import com.maximiliano.maxit.formatFileSize
import com.maximiliano.maxit.getAvailableTools
import com.maximiliano.maxit.getPlatformToolDefinitions
import com.maximiliano.maxit.inference.DownloadError
import com.maximiliano.maxit.inference.DownloadedModel
import com.maximiliano.maxit.inference.EngineState
import com.maximiliano.maxit.inference.InferenceMessage
import com.maximiliano.maxit.inference.LocalInferenceEngine
import com.maximiliano.maxit.inference.LocalModel
import com.maximiliano.maxit.inference.LocalTool
import com.maximiliano.maxit.inference.LocalToolResult
import com.maximiliano.maxit.inference.ModelImportError
import com.maximiliano.maxit.inference.ModelImportResult
import com.maximiliano.maxit.inference.NoModelDownloadedException
import com.maximiliano.maxit.inference.getTotalMemoryBytes
import com.maximiliano.maxit.linux.LinuxDistro
import com.maximiliano.maxit.mcp.McpServerConfig
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.AllServicesFailedException
import com.maximiliano.maxit.network.AnthropicInsufficientCreditsException
import com.maximiliano.maxit.network.AnthropicInvalidApiKeyException
import com.maximiliano.maxit.network.CodexAuth
import com.maximiliano.maxit.network.CodexAuthException
import com.maximiliano.maxit.network.CodexDeviceAuthorization
import com.maximiliano.maxit.network.ContextWindowExceededException
import com.maximiliano.maxit.network.FileTooLargeException
import com.maximiliano.maxit.network.GeminiInvalidApiKeyException
import com.maximiliano.maxit.network.GenericNetworkException
import com.maximiliano.maxit.network.OpenAICompatibleBadRequestException
import com.maximiliano.maxit.network.OpenAICompatibleContentModerationException
import com.maximiliano.maxit.network.OpenAICompatibleEmptyResponseException
import com.maximiliano.maxit.network.OpenAICompatibleGenericException
import com.maximiliano.maxit.network.OpenAICompatibleInvalidApiKeyException
import com.maximiliano.maxit.network.OpenAICompatibleModelNotFoundException
import com.maximiliano.maxit.network.OpenAICompatibleQuotaExhaustedException
import com.maximiliano.maxit.network.OpenAICompatibleRequestTooLargeException
import com.maximiliano.maxit.network.Requests
import com.maximiliano.maxit.network.ServiceCredentials
import com.maximiliano.maxit.network.UnsupportedFileTypeException
import com.maximiliano.maxit.network.dtos.anthropic.extractText
import com.maximiliano.maxit.network.dtos.gemini.extractText
import com.maximiliano.maxit.network.dtos.openaicompatible.extractInlineToolCalls
import com.maximiliano.maxit.network.dtos.openairesponses.OpenAIResponsesResponseDto
import com.maximiliano.maxit.network.toUiError
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.network.tools.ToolInfo
import com.maximiliano.maxit.skills.SkillManager
import com.maximiliano.maxit.skills.SkillManifest
import com.maximiliano.maxit.sms.SmsPoller
import com.maximiliano.maxit.sms.SmsReader
import com.maximiliano.maxit.sms.SmsSendResult
import com.maximiliano.maxit.sms.SmsSender
import com.maximiliano.maxit.tools.NotificationListenerController
import com.maximiliano.maxit.tools.PermissionController
import com.maximiliano.maxit.tools.SkillTools
import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import com.maximiliano.maxit.ui.chat.splitForMessage
import com.maximiliano.maxit.ui.chat.toGeminiMessageDto
import com.maximiliano.maxit.ui.settings.SettingsModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAX_API_RETRIES = 2
private const val ESTIMATED_CHARS_PER_TOKEN = 4

internal fun fitsContextWindow(messages: List<History>, systemPrompt: String?, modelId: String): Boolean {
    val requestChars = messages.sumOf { it.content.length.toLong() } + (systemPrompt?.length ?: 0)
    return requestChars <= ModelCatalog.estimateContextWindow(modelId).toLong() * ESTIMATED_CHARS_PER_TOKEN
}

// Keep dispatch testable without constructing the repository's unrelated platform services.
internal suspend fun LocalInferenceEngine.chatWithToolParserFallback(
    messages: List<InferenceMessage>,
    systemPrompt: String?,
    tools: List<LocalTool>,
    onText: (suspend (String) -> Unit)? = null,
    systemPromptWithoutTools: suspend () -> String? = { systemPrompt },
): String {
    var observed = false
    val guardedTools = tools.map { tool ->
        tool.copy(execute = { args ->
            observed = true
            tool.execute(args)
        })
    }
    try {
        return if (onText == null) {
            chat(messages, systemPrompt, guardedTools)
        } else {
            chatStreaming(messages, systemPrompt, guardedTools) { delta ->
                observed = true
                onText(delta)
            }
        }
    } catch (e: RuntimeException) {
        if (e is CancellationException || observed) throw e
        // A parser-only failure before any output/action can safely retry without tools.
        return if (onText == null) chat(messages, systemPromptWithoutTools(), emptyList()) else chatStreaming(messages, systemPromptWithoutTools(), emptyList(), onText)
    }
}

// Explicit allowlist of tools exposed to the on-device (LiteRT) model. We use a
// hardcoded name list rather than a structural filter because small Gemma models hit
// litert-lm's strict ANTLR function-call parser hard on anything more complex than
// a couple of string parameters. Excluded by design: memory_learn (4 params + enum),
// schedule_task / list_tasks / cancel_task (datetime + cron), the entire email family,
// the heartbeat config tools, and MCP tools.
internal val LOCAL_TOOL_ALLOWLIST = setOf(
    "get_local_time",
    "get_location_from_ip",
    "web_search",
    "open_url",
    "memory_store",
    "memory_forget",
    "memory_reinforce",
    "execute_shell_command",
    SkillTools.ID,
    SkillTools.CREATE_ID,
)

/**
 * The Responses API reports a failed turn inside a 200 body (`status: "failed"`), which would
 * otherwise read as an empty answer. Surface OpenAI's message instead of a generic empty-response
 * error.
 */
private fun OpenAIResponsesResponseDto.throwIfFailed(service: Service) {
    val message = error?.message ?: return
    throw OpenAICompatibleGenericException("${service.displayName}: $message")
}

private data class LoopChatResult(
    val textContent: String,
    val reasoningContent: String? = null,
    val isThinkingContent: Boolean = false,
    val toolCalls: List<ToolCallInfo>,
    val providerBlocks: JsonArray? = null,
    val providerUsage: JsonObject? = null,
)

/** Final answer from a single assistant turn — text and (optionally) the reasoning trace
 * that produced it. Returned from [askWithService] so the caller can persist both. */
private data class AssistantTurn(
    val content: String,
    val reasoningContent: String? = null,
    val messageId: String? = null,
    val providerBlocks: JsonArray? = null,
    val providerUsage: JsonObject? = null,
)

private interface ToolLoopStrategy {
    val providerKey: String? get() = null
    suspend fun chat(history: List<History>, systemPrompt: String?, onText: suspend (String) -> Unit): LoopChatResult

    /**
     * Context budget used to trim the request copy of the history each tool round. Providers that
     * send the history as-is (Gemini, Anthropic) declare their window here; the OpenAI-compatible
     * strategy trims the built message list inside [chat] instead and leaves this null.
     */
    val historyContextWindowTokens: Int? get() = null
}

class RemoteDataRepository(
    private val requests: Requests,
    private val appSettings: AppSettings,
    private val conversationStorage: ConversationStorage,
    private val toolExecutor: ToolExecutor,
    private val memoryStore: MemoryStore,
    private val taskStore: TaskStore,
    private val heartbeatManager: HeartbeatManager,
    private val emailStore: EmailStore,
    private val emailPoller: EmailPoller,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val smsReader: SmsReader,
    private val smsPermissionController: PermissionController,
    private val smsSendPermissionController: PermissionController,
    private val smsSender: SmsSender,
    private val smsDraftStore: SmsDraftStore,
    private val notificationStore: NotificationStore,
    private val notificationListenerController: NotificationListenerController,
    private val mcpServerManager: McpServerManager,
    private val skillManager: SkillManager,
    private val sandboxController: SandboxController,
    private val localInferenceEngine: LocalInferenceEngine? = null,
    private val codexAuth: CodexAuth,
) : DataRepository {

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Returns the tools exposed to the on-device (LiteRT) model. Filtered by name against
     * [LOCAL_TOOL_ALLOWLIST]. Tools the user has disabled in settings (e.g. shell command,
     * which is gated behind `isToolEnabled("execute_shell_command")`) won't appear in
     * `getAvailableTools()` in the first place, so they're naturally excluded.
     */
    private fun getLocalSafeTools(): List<Tool> = getAvailableTools()
        .filter { it.schema.name in LOCAL_TOOL_ALLOWLIST }

    /**
     * Whether the on-device model file for [modelId] carries a tool section in its chat
     * template. A model that doesn't isn't merely worse at tools — it answers *instead* of
     * calling them, inventing whatever the tool would have returned, which is how Qwen3
     * 0.6B reports a fictional time rather than calling `get_local_time`. Withholding the
     * tools makes it a plain chat model, which is what it actually is.
     *
     * The engine reports null when it cannot read the declaration (iOS, or a bundle whose
     * metadata predates it). That is *unknown*, not *no*: keep offering the allowlist there
     * so existing setups behave exactly as before.
     */
    private suspend fun localModelDeclaresTools(modelId: String): Boolean = localInferenceEngine?.supportsToolExecution == true && localInferenceEngine.modelCapabilities(modelId)?.supportsFunctionCalling != false

    // Per-instance model storage: instanceId -> models flow
    private val modelsByInstance: MutableMap<String, MutableStateFlow<List<SettingsModel>>> = mutableMapOf()

    /** Build credentials from per-instance settings */
    private fun instanceCredentials(instanceId: String, service: Service): ServiceCredentials {
        if (service == Service.OpenAICodex) requireCodexInstance(instanceId)
        return ServiceCredentials(
            instanceId = instanceId,
            codexSessionGeneration = if (service == Service.OpenAICodex) codexAuth.sessionGeneration(instanceId) else null,
            apiKey = appSettings.getInstanceApiKey(instanceId),
            modelId = if (service == Service.Free) {
                appSettings.getFreeMode().modelId
            } else {
                appSettings.getInstanceEffectiveModelId(instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
            },
            baseUrl = getInstanceBaseUrl(instanceId, service),
        )
    }

    private val agentMutex = Mutex()
    private val heartbeatMutex = Mutex()
    private val approvalController = ToolApprovalController()
    override val toolApprovals = approvalController.requests
    override fun resolveToolApproval(id: String, approved: Boolean) = approvalController.resolve(id, approved)
    private val selectionLock = ConversationLock()
    private var selectedStorageGeneration = conversationStorage.generation()
    override val chatHistory: MutableStateFlow<List<History>> = MutableStateFlow(emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    override val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _fallbackStatus = MutableStateFlow<FallbackStatus?>(null)
    override val fallbackStatus: StateFlow<FallbackStatus?> = _fallbackStatus

    override val savedConversations: StateFlow<List<Conversation>> = conversationStorage.conversations

    override fun getConfiguredServiceInstances(): List<ServiceInstance> = appSettings.getConfiguredServiceInstances().filter { Service.fromId(it.serviceId) != Service.Free }

    override fun addConfiguredService(serviceId: String): ServiceInstance {
        val instanceId = appSettings.generateInstanceId(serviceId)
        val instance = ServiceInstance(instanceId = instanceId, serviceId = serviceId)
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.add(instance)
        appSettings.setConfiguredServiceInstances(current)
        appSettings.setFreeServicePrimary(false)
        return instance
    }

    override fun removeConfiguredService(instanceId: String) {
        codexAuth.signOut(instanceId)
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.removeAll { it.instanceId == instanceId }
        appSettings.setConfiguredServiceInstances(current)
        appSettings.removeInstanceSettings(instanceId)
        modelsByInstance.remove(instanceId)
    }

    override fun reorderConfiguredServices(orderedInstanceIds: List<String>) {
        val current = appSettings.getConfiguredServiceInstances()
        val byId = current.associateBy { it.instanceId }
        val reordered = orderedInstanceIds.mapNotNull { byId[it] }
        appSettings.setConfiguredServiceInstances(reordered)
    }

    override fun getServiceEntries(): List<ServiceEntry> = getConfiguredServiceInstances().map { instance ->
        val service = Service.fromId(instance.serviceId)
        val modelId = appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty {
            appSettings.getSelectedModelId(service)
        }
        ServiceEntry(
            instanceId = instance.instanceId,
            serviceId = service.id,
            serviceName = service.displayName,
            modelId = modelId,
            icon = service.icon,
        )
    }

    override fun isFreeFallbackEnabled(): Boolean = appSettings.isFreeFallbackEnabled()

    override fun setFreeFallbackEnabled(enabled: Boolean) {
        appSettings.setFreeFallbackEnabled(enabled)
    }

    override fun getFreeMode(): FreeMode = appSettings.getFreeMode()

    override fun setFreeMode(mode: FreeMode) {
        appSettings.setFreeMode(mode)
    }

    override fun isFreeServicePrimary(): Boolean = appSettings.isFreeServicePrimary()

    override fun setFreeServicePrimary(primary: Boolean) {
        appSettings.setFreeServicePrimary(primary)
    }

    // Per-instance settings
    override fun getInstanceApiKey(instanceId: String): String = appSettings.getInstanceApiKey(instanceId)

    override fun updateInstanceApiKey(instanceId: String, apiKey: String) {
        appSettings.setInstanceApiKey(instanceId, apiKey)
    }

    override fun isCodexSignedIn(instanceId: String): Boolean = codexAuth.isSignedIn(instanceId)

    override suspend fun beginCodexSignIn(instanceId: String): CodexDeviceAuthorization {
        requireCodexInstance(instanceId)
        return codexAuth.beginSignIn(instanceId)
    }

    override suspend fun completeCodexSignIn(instanceId: String, authorization: CodexDeviceAuthorization) {
        requireCodexInstance(instanceId)
        codexAuth.completeSignIn(instanceId, authorization)
    }

    override fun signOutCodex(instanceId: String) {
        codexAuth.signOut(instanceId)
        modelsByInstance[instanceId]?.value = emptyList()
    }

    private fun requireCodexInstance(instanceId: String) {
        check(currentPlatform != Platform.Web) { "ChatGPT sign-in requires the Android or desktop app." }
        require(getConfiguredServiceInstances().any { it.instanceId == instanceId && it.serviceId == Service.OpenAICodex.id })
    }

    override fun getInstanceBaseUrl(instanceId: String, service: Service): String {
        val url = appSettings.getInstanceBaseUrl(instanceId)
        return url.ifBlank { if (service is Service.OpenAICompatible) Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL else "" }
    }

    override fun updateInstanceBaseUrl(instanceId: String, baseUrl: String) {
        appSettings.setInstanceBaseUrl(instanceId, baseUrl)
    }

    override fun getInstanceModels(instanceId: String, service: Service): StateFlow<List<SettingsModel>> = modelsByInstance.getOrPut(instanceId) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val defaultSettingsModels = service.defaultModels.map {
            SettingsModel(
                id = it.id,
                subtitle = it.subtitle,
                descriptionRes = it.descriptionRes,
                isSelected = it.id == selectedModelId,
            )
        }
        val models = if (selectedModelId.isNotEmpty() && defaultSettingsModels.none { it.id == selectedModelId }) {
            listOf(
                SettingsModel(
                    id = selectedModelId,
                    subtitle = "",
                    isSelected = true,
                    isManualEntry = true,
                ),
            ) + defaultSettingsModels
        } else {
            defaultSettingsModels
        }
        MutableStateFlow(models)
    }

    override fun updateInstanceSelectedModel(instanceId: String, service: Service, modelId: String) {
        appSettings.setInstanceModelId(instanceId, modelId)
        modelsByInstance[instanceId]?.update { models ->
            ensureSelectedModelPresent(models, modelId)
        }
        // Free the previously-loaded on-device model as soon as the user picks a new one.
        // Deferring until the next chat would briefly hold both models' GPU buffers resident
        // and the driver's lazy reclaim can push us past LMK thresholds on mid-range devices.
        if (service.isOnDevice && localInferenceEngine?.currentModelId?.let { it != modelId } == true) {
            localInferenceEngine.releaseInBackground()
        }
    }

    override fun getInstanceUseCustomModel(instanceId: String): Boolean = appSettings.getInstanceUseCustomModel(instanceId)

    override fun updateInstanceUseCustomModel(instanceId: String, useCustom: Boolean) {
        if (useCustom && appSettings.getInstanceCustomModelId(instanceId).isBlank()) {
            // Prefill from list selection so the field is not empty when the checkbox is first enabled.
            val listModelId = appSettings.getInstanceModelId(instanceId)
            if (listModelId.isNotBlank()) {
                appSettings.setInstanceCustomModelId(instanceId, listModelId)
            }
        }
        appSettings.setInstanceUseCustomModel(instanceId, useCustom)
    }

    override fun getInstanceCustomModelId(instanceId: String): String = appSettings.getInstanceCustomModelId(instanceId)

    override fun updateInstanceCustomModelId(instanceId: String, modelId: String) {
        appSettings.setInstanceCustomModelId(instanceId, modelId)
    }

    override fun clearInstanceModels(instanceId: String, service: Service) {
        modelsByInstance[instanceId]?.update { emptyList() }
    }

    override suspend fun validateConnection(service: Service, instanceId: String) {
        if (service.isOnDevice) {
            fetchInstanceModels(service, instanceId)
            return
        }
        val creds = instanceCredentials(instanceId, service)
        when (service) {
            Service.Free -> { /* Always valid */ }

            Service.OpenRouter -> {
                requests.validateOpenRouterApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            Service.Requesty -> {
                requests.validateRequestyApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            Service.Perplexity -> {
                // No Sonar `/models` list — validate the key, then load the curated defaults.
                requests.validatePerplexityApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            else -> fetchInstanceModels(service, instanceId)
        }
    }

    private suspend fun fetchInstanceModels(service: Service, instanceId: String) {
        when (service) {
            Service.OpenAICodex -> {
                requireCodexInstance(instanceId)
                fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                    mapOpenAICompatibleModels(requests.getCodexModels(creds).getOrThrow().data, service, selectedModelId)
                }
            }

            Service.Gemini -> fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                mapGeminiModels(requests.getGeminiModels(creds).getOrThrow().models, selectedModelId)
            }

            Service.Anthropic -> fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                mapAnthropicModels(requests.getAnthropicModels(creds).getOrThrow().data, selectedModelId)
            }

            Service.Free -> { /* No model listing */ }

            Service.LiteRT -> {
                val engine = localInferenceEngine ?: return
                val selectedModelId = appSettings.getInstanceModelId(instanceId)
                val downloaded = engine.getDownloadedModels()
                val models = downloaded.map {
                    SettingsModel(
                        id = it.id,
                        subtitle = "${it.displayName} (${formatFileSize(it.sizeBytes)})",
                        isSelected = it.id == selectedModelId,
                    )
                }
                updateModelsForInstance(instanceId, models, service)
            }

            else -> {
                if (service.modelsUrl != null) {
                    fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                        mapOpenAICompatibleModels(
                            requests.getOpenAICompatibleModels(service, creds).getOrThrow().data,
                            service,
                            selectedModelId,
                        )
                    }
                } else if (service.defaultModels.isNotEmpty()) {
                    val selectedModelId = appSettings.getInstanceModelId(instanceId)
                    val models = service.defaultModels.map {
                        SettingsModel(
                            id = it.id,
                            subtitle = it.subtitle,
                            descriptionRes = it.descriptionRes,
                            isSelected = it.id == selectedModelId,
                        )
                    }
                    updateModelsForInstance(instanceId, models, service)
                }
            }
        }
    }

    /**
     * Fetches [service]'s model list for [instanceId] and publishes it. [fetchAndMap] is the only
     * per-provider part: it issues the request and maps the response to [SettingsModel]s, given
     * the instance credentials and the currently selected model id.
     */
    private suspend fun fetchModelsForInstance(
        service: Service,
        instanceId: String,
        fetchAndMap: suspend (ServiceCredentials, String) -> List<SettingsModel>,
    ) {
        val creds = instanceCredentials(instanceId, service)
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        updateModelsForInstance(instanceId, fetchAndMap(creds, selectedModelId))
    }

    private fun updateModelsForInstance(instanceId: String, models: List<SettingsModel>, service: Service? = null) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val withSelected = ensureSelectedModelPresent(models, selectedModelId)
        val flow = modelsByInstance.getOrPut(instanceId) { MutableStateFlow(emptyList()) }
        flow.update { withSelected }
        // Only auto-pick when nothing is stored yet — never overwrite a custom / unlisted model id.
        if (withSelected.isNotEmpty() && withSelected.none { it.isSelected }) {
            val default = pickDefaultModel(withSelected, service)
            if (default != null) {
                appSettings.setInstanceModelId(instanceId, default.id)
                flow.update { m -> m.map { it.copy(isSelected = it.id == default.id) } }
            }
        }
    }

    private fun pickDefaultModel(models: List<SettingsModel>, service: Service? = null): SettingsModel? {
        val defaultModel = service?.defaultModel
        if (defaultModel != null) {
            models.firstOrNull { it.id == defaultModel }?.let { return it }
        }
        return models.firstOrNull { it.id.contains("kimi-k2.5", ignoreCase = true) }
            ?: models.firstOrNull()
    }

    private suspend fun askWithLocalEngine(
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
        onText: (suspend (String) -> Unit)? = null,
        allowTools: Boolean = true,
        systemPromptWithoutTools: suspend (modelId: String) -> String? = { systemPrompt },
    ): String {
        val engine = localInferenceEngine
            ?: throw IllegalStateException("On-device inference not available on this platform")

        val modelId = appSettings.getInstanceModelId(instanceId)
        val downloadedModels = engine.getDownloadedModels()
        val model = downloadedModels.find { it.id == modelId }
            ?: downloadedModels.firstOrNull()
            ?: throw NoModelDownloadedException()

        val catalogModel = (engine.getAvailableModels() + engine.getImportedLocalModels()).find { it.id == model.id }
        val storedContext = appSettings.getModelContextTokens(model.id)
        val contextTokens = if (storedContext > 0) storedContext else catalogModel?.defaultContextTokens ?: 0

        val needsInit = engine.engineState.value != EngineState.READY || engine.currentModelId != model.id
        if (needsInit) {
            val statusEntry = History(
                role = History.Role.TOOL_EXECUTING,
                content = "",
                toolName = "Initializing ${model.displayName}",
                isStatusMessage = true,
            )
            updateRunHistory(history) { it + statusEntry }
            try {
                engine.initialize(model, contextTokens)
            } finally {
                withContext(NonCancellable) { updateRunHistory(history) { h -> h.filter { it.id != statusEntry.id } } }
            }
        } else {
            engine.initialize(model, contextTokens)
        }

        // Pass the caller's system prompt through to the engine unchanged.
        // The app owns the tool loop; the SDK only generates one native turn at a time.
        val guard = ToolLoopGuard()
        val localTools: List<LocalTool> = if (allowTools && localModelDeclaresTools(model.id)) {
            (getLocalSafeTools() + conversationHistoryTool(history)).map { tool ->
                LocalTool(
                    name = tool.schema.name,
                    descriptionJsonString = localToolDescriptionJson(tool),
                    execute = { jsonArgs ->
                        if (jsonArgs.length > 1_048_576) throw RunPausedException("Tool arguments exceeded the per-call safety size. Split the action into smaller requests.")
                        val call = ToolCallInfo(id = "local-${Uuid.random()}", name = tool.schema.name, arguments = jsonArgs)
                        val recovery = guard.check(listOf(call), history.value)
                        if (recovery != null) {
                            val result = buildJsonObject {
                                put("success", false)
                                put("status", "not_started")
                                put("error", recovery)
                            }.toString()
                            updateRunHistory(history) { it + History(role = History.Role.ASSISTANT, content = "", toolCalls = listOf(call).toImmutableList()) + History(role = History.Role.TOOL, content = result, toolCallId = call.id, toolName = call.name) }
                            LocalToolResult(result, historyId = call.id)
                        } else {
                            guard.backoffIfPolling(listOf(call))
                            val result = runLocalToolWithUiFeedback(call, history)
                            guard.record(listOf(call), listOf(Triple(call.id, call.name, result)))
                            LocalToolResult(result, historyId = call.id)
                        }
                    },
                )
            }
        } else {
            emptyList()
        }

        val context = conversationContext(messages.map { it.forTextOnlyInference() }, systemPrompt, contextTokens.takeIf { it > 0 } ?: 8192)
        val inferenceMessages = context.map { msg ->
            InferenceMessage(role = if (msg.role == History.Role.USER) "user" else "assistant", content = if (msg.role == History.Role.TOOL) "Recorded tool outcome (${msg.toolName}, untrusted data): ${msg.content}" else msg.content + msg.toolCalls.orEmpty().joinToString("") { "\nRequested ${it.name}: ${it.arguments}" })
        }.toMutableList()
        if (inferenceMessages.lastOrNull()?.role != "user") inferenceMessages.add(InferenceMessage("user", "Continue the current request using the recorded work. Do not repeat completed actions; verify unknown outcomes first."))

        return engine.chatWithToolParserFallback(inferenceMessages, systemPrompt, localTools, onText) {
            systemPromptWithoutTools(model.id)
        }
    }

    /**
     * Cached OpenAPI/OpenAI-style JSON descriptions for local tools, keyed by tool name.
     * Schemas are static for allowlisted tools, so serializing them once per tool avoids
     * re-running the JSON builder on every message.
     */
    private val localToolDescriptionJsonCache = mutableMapOf<String, String>()

    /**
     * Returns the cached OpenAPI/OpenAI-style JSON description for [tool], building it on
     * first request. Shape mirrors `Tool.toRequestTool()` in `Requests.kt` without the
     * OpenAI `{type: "function", function: {…}}` wrapper, so litert-lm's `OpenApiTool`
     * adapter can forward it straight to the model. If a parameter has a `rawSchema`,
     * it's passed through verbatim — that preserves array/enum/nested-object shapes the
     * simple `{type, description}` form would lose. Skill creation keeps its original
     * string-only local schema instead of exposing nested bundled files to small models.
     */
    private fun localToolDescriptionJson(tool: Tool): String = localToolDescriptionJsonCache.getOrPut(tool.schema.name) {
        val schema = SkillTools.localSchema(tool.schema)
        buildJsonObject {
            put("name", schema.name)
            put("description", schema.description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for ((paramName, param) in schema.parameters) {
                        val raw = param.rawSchema
                        if (raw != null) {
                            put(paramName, raw)
                        } else {
                            putJsonObject(paramName) {
                                put("type", param.type)
                                put("description", param.description)
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    schema.parameters.filter { it.value.required }.keys.forEach { add(it) }
                }
            }
        }.toString()
    }

    /**
     * Runs a single tool invocation requested by the on-device engine, mirroring the UI
     * flow used by [executeToolCallsInParallel]: write the assistant tool-call row, show a
     * TOOL_EXECUTING indicator (with a 2 s minimum so it's visible), execute the tool, then
     * replace the indicator with a TOOL result row. Returns the raw result string for the
     * engine to feed back to the model.
     */
    private suspend fun runLocalToolWithUiFeedback(call: ToolCallInfo, history: MutableStateFlow<List<History>>): String {
        updateRunHistory(history) { it + History(role = History.Role.ASSISTANT, content = "", toolCalls = persistentListOf(call)) }
        return executeRecordedTool(call.id, call.name, call.arguments, activeConversationId(), history).third
    }

    private suspend fun askWithService(
        service: Service,
        messages: List<History>,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val creds = instanceCredentials(instanceId, service)
        // Build context for the actual dispatch target, not the primary provider before fallback.
        val systemPrompt = buildActiveSystemPrompt(
            variant = if (service.isOnDevice) SystemPromptVariant.CHAT_LOCAL else SystemPromptVariant.CHAT_REMOTE,
            service = service,
            modelId = if (service.isOnDevice) appSettings.getInstanceModelId(instanceId) else creds.modelId,
        )
        if (service.isOnDevice) {
            // No retry: local-inference failures are deterministic, and this path mutates
            // chat history through tool execution, so a replay could re-run tools.
            val stream = StreamedAssistant(history, CoroutineScope(currentCoroutineContext()))
            try {
                val answer = askWithLocalEngine(messages, systemPrompt, instanceId, history, stream::append) { modelId ->
                    buildActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL, service, modelId, toolsEnabled = false)
                }
                stream.complete(History(role = History.Role.ASSISTANT, content = answer))
                return AssistantTurn(answer, messageId = stream.id)
            } finally {
                stream.preservePartial()
            }
        }

        val tools = if (supportsTools(creds.modelId)) getAvailableTools() + conversationHistoryTool(history) else emptyList()

        if (tools.isEmpty()) {
            return plainChat(service, creds, conversationContext(messages, systemPrompt, ModelCatalog.estimateContextWindow(creds.modelId)), systemPrompt, strictEmptyResponse = true, history = history)
        }

        return when (service) {
            Service.Gemini -> handleGeminiChatWithTools(creds, messages, tools, systemPrompt, history)
            Service.Anthropic -> handleAnthropicChatWithTools(creds, messages, tools, systemPrompt, history)
            else -> handleOpenAICompatibleChatWithTools(service, creds, messages, tools, systemPrompt, history)
        }
    }

    /**
     * The conversation a request belongs to: an explicit coroutine-context id (set by
     * askWithTools for heartbeat / scheduled runs) wins over the globally active chat id, so
     * background runs aren't attributed to whatever chat the user is viewing. Null before any
     * conversation exists. Used as the upstream session id for providers that require one.
     */
    private suspend fun activeConversationId(): String? = currentConversationIdOrNull() ?: _currentConversationId.value

    /**
     * One assistant turn with no tools declared, dispatched to whichever API [service] speaks.
     * The three providers differ only in message shape and text extraction, so every caller
     * needing a plain completion — the no-tools path above, the silent asks, and the
     * Gemini/Anthropic tool-loop bailouts — funnels through here.
     *
     * [retry] wraps the call in [retryApiCall]; silent callers skip it because they run on a
     * caller-supplied deadline. [strictEmptyResponse] turns a missing OpenAI-compatible message
     * or content into [OpenAICompatibleEmptyResponseException] — the visible chat path wants that
     * error surfaced, silent callers prefer empty text.
     */
    private suspend fun plainChat(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<History>,
        systemPrompt: String?,
        retry: Boolean = true,
        strictEmptyResponse: Boolean = false,
        history: MutableStateFlow<List<History>>? = null,
    ): AssistantTurn {
        suspend fun <T> call(block: suspend () -> T): T = if (retry) retryApiCall(block) else block()
        val stream = history?.let { StreamedAssistant(it, CoroutineScope(currentCoroutineContext())) }
        val compatibleMessages = compatibleToolHistory(messages, emptySet())
        val onText: (suspend (String) -> Unit)? = stream?.let { it::append }
        try {
            val turn = when (service) {
                Service.Gemini -> {
                    val response = call {
                        requests.geminiChat(
                            credentials = credentials,
                            messages = compatibleMessages.map { it.toGeminiMessageDto() },
                            systemInstruction = systemPrompt,
                            onText = onText,
                        ).getOrThrow()
                    }
                    AssistantTurn(response.extractText(), providerUsage = response.usageMetadata)
                }

                Service.Anthropic -> {
                    val response = call {
                        requests.anthropicChat(
                            credentials = credentials,
                            messages = buildAnthropicMessages(compatibleMessages),
                            systemInstruction = systemPrompt,
                            onText = onText,
                        ).getOrThrow()
                    }
                    AssistantTurn(response.extractText(), providerBlocks = response.rawContent, providerUsage = response.usage)
                }

                else -> {
                    // No tools on this request — strip any historic tool_calls so Groq's strict
                    // validator doesn't see calls to tools we no longer declare.
                    val openAIMessages = buildOpenAIMessages(service, messages, systemPrompt, credentials.modelId, declaredToolNames = emptySet())
                    if (requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)) {
                        val response = call {
                            requests.openAIResponses(service, credentials, toResponsesInput(openAIMessages), sessionId = activeConversationId(), onText = onText).getOrThrow()
                        }
                        response.throwIfFailed(service)
                        val content = response.outputText
                        if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                        AssistantTurn(content.orEmpty(), response.reasoningSummary, providerBlocks = response.rawOutput, providerUsage = response.usage)
                    } else {
                        val sessionId = activeConversationId()
                        val response = call {
                            requests.openAICompatibleChat(service, credentials, openAIMessages, sessionId = sessionId, onText = onText).getOrThrow()
                        }
                        val message = response.choices.firstOrNull()?.message
                        val content = message?.effectiveContent
                        if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                        AssistantTurn(content.orEmpty(), message?.reasoningTraceFor(content), providerBlocks = message?.reasoningDetails, providerUsage = response.usage)
                    }
                }
            }
            stream?.complete(History(role = History.Role.ASSISTANT, content = turn.content, reasoningContent = turn.reasoningContent, providerBlocks = turn.providerBlocks, providerUsage = turn.providerUsage, providerKey = "${service.id}:${credentials.instanceId}:${credentials.modelId}"))
            return turn.copy(messageId = stream?.id)
        } finally {
            stream?.preservePartial()
        }
    }

    private fun hasValidInstanceApiKey(instanceId: String, service: Service): Boolean {
        if (service == Service.Free) return false
        if (service == Service.OpenAICodex) return currentPlatform != Platform.Web && codexAuth.isSignedIn(instanceId)
        if (service.isOnDevice) return true
        if (!service.requiresApiKey && !service.supportsOptionalApiKey) return true
        if (service.requiresApiKey) return appSettings.getInstanceApiKey(instanceId).isNotBlank()
        return true // Optional API key services are always valid
    }

    private data class FallbackEntry(val instanceId: String, val service: Service)

    private fun getOrderedFallbackEntries(): List<FallbackEntry> {
        val instances = getConfiguredServiceInstances()
        val entries = instances.map { FallbackEntry(instanceId = it.instanceId, service = Service.fromId(it.serviceId)) }
            .filter { it.service != Service.Free }
            .filter { !it.service.isOnDevice || localInferenceEngine != null }
        // On-device models are only tried as the primary service, never as a fallback
        // target — falling into one would silently start a heavy model load the user
        // didn't ask for (mirrors the guard that keeps on-device errors from silently
        // falling back to cloud services).
        return entries.filterIndexed { index, entry -> index == 0 || !entry.service.isOnDevice }
    }

    override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?): History? = runForegroundRequest(question, files, uiSubmission, activeSkillId)

    override suspend fun regenerate(): History? = runForegroundRequest(null, emptyList(), null, null, regenerate = true)

    private suspend fun runForegroundRequest(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?, regenerate: Boolean = false): History? {
        conversationStorage.loadConversations()
        val (conversationId, selectedGeneration, regenerateAfter) = selectionLock.locked {
            val id = _currentConversationId.value ?: Uuid.random().toString().also { setCurrentConversationId(it) }
            Triple(id, selectedStorageGeneration, if (regenerate) chatHistory.value.lastOrNull { it.role == History.Role.USER && !it.superseded }?.id else null)
        }
        return agentMutex.withLock {
            conversationStorage.loadConversations()
            if (selectedGeneration != conversationStorage.generation()) throw CancellationException("Conversation history was replaced. Reopen the saved conversation before continuing.")
            val initial = savedConversations.value.find { it.id == conversationId }?.messages?.map { it.toHistory() }
                ?: selectionLock.locked { if (_currentConversationId.value == conversationId) chatHistory.value else emptyList() }
            val run = newConversationRun(conversationId, initial, interactive = true)
            withContext(run + ConversationIdElement(conversationId)) {
                pendingActiveSkillId = activeSkillId?.takeIf { skillManager.getSkill(it) != null }
                try {
                    askInternal(question, files, uiSubmission, run.history, regenerateAfter)
                    run.finish(MessageState.COMPLETE, "")
                    run.history.value.lastOrNull { it.runId == run.id && it.role == History.Role.ASSISTANT && !it.isStatusMessage && !it.superseded && !it.isThinking && it.content.isNotEmpty() && it.state == MessageState.COMPLETE }
                } catch (e: RunPausedException) {
                    run.finish(MessageState.PAUSED, e.message.orEmpty())
                    null
                } catch (e: CancellationException) {
                    run.finish(MessageState.INTERRUPTED, "Stopped. Received text and completed tool results are saved. Verify unknown action outcomes before repeating them.")
                    throw e
                } catch (e: Exception) {
                    run.finish(MessageState.FAILED, "The run did not finish. Saved text and tool outcomes are preserved; unknown outcomes must be verified before retrying.")
                    throw e
                } finally {
                    pendingActiveSkillId = null
                }
            }
        }
    }

    private fun newConversationRun(conversationId: String, initial: List<History>, interactive: Boolean = false): ConversationRun {
        val generation = conversationStorage.generation()
        val type = if (interactiveModeFlag) Conversation.TYPE_INTERACTIVE else Conversation.TYPE_CHAT
        return ConversationRun(
            id = Uuid.random().toString(),
            conversationId = conversationId,
            initial = initial,
            interactive = interactive,
            persist = { persistHistory(conversationId, it, generation, type) },
            publish = { entries ->
                if (!interactive && entries.lastOrNull { it.isStatusMessage }?.state in setOf(MessageState.PAUSED, MessageState.INTERRUPTED, MessageState.FAILED)) _hasUnreadHeartbeat.value = true
                selectionLock.locked {
                    if (_currentConversationId.value == conversationId) {
                        selectedStorageGeneration = generation
                        chatHistory.value = entries
                    }
                }
            },
        )
    }

    private var pendingActiveSkillId: String? = null

    private suspend fun askInternal(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, history: MutableStateFlow<List<History>>, regenerateAfter: String?) {
        // Process every attached file: classify, compress/encode, and build an Attachment.
        // readBytes() is suspend, so this happens before the StateFlow.update block.
        val attachments = files.map { file ->
            val fileMimeType = file.mimeType()?.toString()
            val fileName = file.name

            val category = classifyFile(fileMimeType, fileName)
            if (category == FileCategory.UNSUPPORTED) throw UnsupportedFileTypeException()

            // Reject oversized files by stat size before readBytes(), which would otherwise
            // allocate a ByteArray large enough to OOM the process on multi-GB inputs.
            val rawSizeLimit = when (category) {
                FileCategory.TEXT -> MAX_TEXT_FILE_BYTES.toLong()
                FileCategory.PDF -> MAX_PDF_BYTES.toLong()
                FileCategory.IMAGE -> MAX_RAW_IMAGE_BYTES.toLong()
                FileCategory.UNSUPPORTED -> 0L
            }
            if (file.size() > rawSizeLimit) throw FileTooLargeException()

            val rawBytes = file.readBytes()

            when (category) {
                FileCategory.IMAGE -> {
                    val compressed = compressImageBytes(rawBytes, fileMimeType ?: "image/jpeg")
                    // compressImageBytes can fall back to the original bytes on failure or on
                    // platforms without compression — guard against Base64 OOM for oversized input.
                    if (compressed.size > MAX_IMAGE_BYTES) throw FileTooLargeException()
                    Attachment(
                        data = Base64.encode(compressed),
                        mimeType = "image/jpeg",
                        fileName = null,
                    )
                }

                FileCategory.TEXT -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = fileMimeType ?: "text/plain",
                    fileName = fileName,
                )

                FileCategory.PDF -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = "application/pdf",
                    fileName = fileName,
                )

                FileCategory.UNSUPPORTED -> throw UnsupportedFileTypeException()
            }
        }.toImmutableList()

        updateRunHistory(history) { entries ->
            val base = if (regenerateAfter != null) {
                val anchor = entries.indexOfFirst { it.id == regenerateAfter && !it.superseded }
                check(anchor >= 0) { "The request to regenerate was replaced. Reopen the saved conversation before continuing." }
                entries.mapIndexed { index, entry -> if (index > anchor) entry.copy(superseded = true) else entry }
            } else {
                entries
            }
            base + buildList {
                if (question != null) add(History(role = History.Role.USER, content = question, attachments = attachments, uiSubmission = uiSubmission))
                add(History(role = History.Role.ASSISTANT, content = "", isStatusMessage = true, state = MessageState.RUNNING))
            }
        }

        if (question != null) currentCoroutineContext()[ConversationInputAccepted]?.notify?.invoke()
        val fallbackEntries = getOrderedFallbackEntries().filter { hasValidInstanceApiKey(it.instanceId, it.service) }
        if (fallbackEntries.isEmpty()) {
            if (currentPlatform != Platform.Web && getConfiguredServiceInstances().any { it.serviceId == Service.OpenAICodex.id }) {
                throw CodexAuthException("Sign in with ChatGPT under OpenAI Codex in Settings before sending a message.")
            }
            throw GenericNetworkException("Configure a model provider in Settings before sending a message.")
        }

        var lastException: Exception? = null
        var fallbackServiceName: String? = null

        try {
            for ((index, entry) in fallbackEntries.withIndex()) {
                // No retry wrapper here: each network call retries inside askWithService.
                // Retrying the whole call would re-enter the tool loop against a chat
                // history already mutated by the failed attempt.
                val turn = try {
                    askWithService(entry.service, history.value, entry.instanceId, history)
                } catch (e: Exception) {
                    if (e is CancellationException || e is RunPausedException || e is ConversationWriteException || e is com.maximiliano.maxit.network.StreamInterruptedException) throw e
                    // Never fail over after receiving partial output or requesting an action.
                    val runId = currentCoroutineContext()[ConversationRun]?.id
                    if (entry.service.isOnDevice || history.value.any { it.runId == runId && !it.isStatusMessage && it.role == History.Role.ASSISTANT }) throw e
                    lastException = e
                    _fallbackStatus.value = FallbackStatus(
                        serviceName = entry.service.displayName,
                        errorReason = e.toUiError(),
                        nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                    )
                    continue
                }
                if (index > 0) {
                    fallbackServiceName = entry.service.displayName
                }
                updateRunHistory(history) { entries ->
                    if (turn.messageId != null) {
                        entries.map { if (it.id == turn.messageId) it.copy(fallbackServiceName = fallbackServiceName) else it }
                    } else {
                        entries + History(role = History.Role.ASSISTANT, content = turn.content, reasoningContent = turn.reasoningContent, fallbackServiceName = fallbackServiceName)
                    }
                }
                return
            }

            throw if (fallbackEntries.size > 1 && lastException != null) {
                AllServicesFailedException()
            } else {
                lastException ?: OpenAICompatibleEmptyResponseException()
            }
        } finally {
            _fallbackStatus.value = null
        }
    }

    private suspend fun handleOpenAICompatibleChatWithTools(
        service: Service,
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val declaredToolNames = tools.map { it.schema.name }.toSet()
        // GPT-5.6 and friends reject function tools on chat completions; the same messages are
        // translated to Responses API items instead. Everything before the wire call — prompt
        // assembly, tool-call pairing, context trimming — is shared.
        val useResponsesApi = requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)
        val strategy = object : ToolLoopStrategy {
            override val providerKey = "${service.id}:${credentials.instanceId}:${credentials.modelId}"
            override val historyContextWindowTokens = contextWindowTokens
            override suspend fun chat(history: List<History>, systemPrompt: String?, onText: suspend (String) -> Unit): LoopChatResult {
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames, providerKey), contextWindowTokens)
                if (useResponsesApi) {
                    val response = retryApiCall {
                        requests.openAIResponses(service, credentials, toResponsesInput(msgs), tools, sessionId = activeConversationId(), onText = onText).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val text = response.outputText
                    val calls = response.functionCalls.map { fc ->
                        ToolCallInfo(
                            id = fc.callId ?: Uuid.random().toString(),
                            name = fc.name.orEmpty(),
                            arguments = fc.arguments ?: "{}",
                        )
                    }
                    if (text == null && calls.isEmpty()) throw OpenAICompatibleEmptyResponseException()
                    return LoopChatResult(
                        textContent = text.orEmpty(),
                        reasoningContent = response.reasoningSummary,
                        toolCalls = calls,
                        providerBlocks = response.rawOutput,
                        providerUsage = response.usage,
                    )
                }
                val sessionId = activeConversationId()
                val response = retryApiCall {
                    requests.openAICompatibleChat(service, credentials, msgs, tools, sessionId = sessionId, onText = onText).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message ?: throw OpenAICompatibleEmptyResponseException()
                var calls = message.toolCalls.orEmpty().map { tc ->
                    ToolCallInfo(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
                }
                var textContent = message.effectiveContent ?: ""
                if (calls.isEmpty() && textContent.contains("<tool_call>")) {
                    val extracted = extractInlineToolCalls(textContent, tools)
                    if (extracted.calls.isNotEmpty()) {
                        textContent = extracted.cleanedText
                        calls = extracted.calls.map {
                            ToolCallInfo(
                                id = "inline-${Uuid.random()}",
                                name = it.name,
                                arguments = it.arguments,
                            )
                        }
                    }
                }
                return LoopChatResult(
                    textContent = textContent,
                    reasoningContent = message.reasoningTraceFor(textContent),
                    isThinkingContent = message.isContentFromReasoning,
                    toolCalls = calls,
                    providerBlocks = message.reasoningDetails,
                    providerUsage = response.usage,
                )
            }
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun handleGeminiChatWithTools(
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val strategy = object : ToolLoopStrategy {
            override val providerKey = "gemini:${credentials.instanceId}:${credentials.modelId}"
            override suspend fun chat(history: List<History>, systemPrompt: String?, onText: suspend (String) -> Unit): LoopChatResult {
                val geminiMessages = compatibleToolHistory(history, tools.map { it.schema.name }.toSet(), providerKey).map { it.toGeminiMessageDto() }
                val response = retryApiCall {
                    requests.geminiChat(
                        credentials = credentials,
                        messages = geminiMessages,
                        tools = tools,
                        systemInstruction = systemPrompt,
                        onText = onText,
                    ).getOrThrow()
                }
                val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                val partsWithFunctionCalls = parts.filter { it.functionCall != null }
                val toolCallInfos = partsWithFunctionCalls.map { part ->
                    val fc = part.functionCall!!
                    val argsJson = fc.args?.let { JsonObject(it).toString() } ?: "{}"
                    ToolCallInfo(
                        id = "gemini-${Uuid.random()}",
                        name = fc.name,
                        arguments = argsJson,
                        thoughtSignature = part.thoughtSignature,
                    )
                }
                val textContent = parts.filterNot { it.isThought }.mapNotNull { it.text }.joinToString("\n")
                return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos, providerUsage = response.usageMetadata)
            }

            override val historyContextWindowTokens = contextWindowTokens
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun handleAnthropicChatWithTools(
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val strategy = object : ToolLoopStrategy {
            override val providerKey = "anthropic:${credentials.instanceId}:${credentials.modelId}"
            override suspend fun chat(history: List<History>, systemPrompt: String?, onText: suspend (String) -> Unit): LoopChatResult {
                val msgs = buildAnthropicMessages(compatibleToolHistory(history, tools.map { it.schema.name }.toSet(), providerKey))
                val response = retryApiCall {
                    requests.anthropicChat(
                        credentials = credentials,
                        messages = msgs,
                        tools = tools,
                        systemInstruction = systemPrompt,
                        onText = onText,
                    ).getOrThrow()
                }
                val toolUseBlocks = response.content.filter { it.type == "tool_use" }
                val toolCallInfos = toolUseBlocks.map { block ->
                    val argsJson = block.input?.toString() ?: "{}"
                    ToolCallInfo(
                        id = block.id ?: "anthropic-${Uuid.random()}",
                        name = block.name ?: "unknown",
                        arguments = argsJson,
                    )
                }
                val textContent = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos, providerBlocks = response.rawContent ?: JsonArray(response.content.map { Json.encodeToJsonElement(com.maximiliano.maxit.network.dtos.anthropic.AnthropicChatResponseDto.ContentBlock.serializer(), it) }), providerUsage = response.usage)
            }

            override val historyContextWindowTokens = contextWindowTokens
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun runToolLoop(
        strategy: ToolLoopStrategy,
        systemPrompt: String?,
        history: MutableStateFlow<List<History>>,
    ): AssistantTurn {
        val guard = ToolLoopGuard()
        while (true) {
            currentCoroutineContext().ensureActive()
            val visible = conversationContext(history.value, systemPrompt, strategy.historyContextWindowTokens ?: ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS, getAvailableTools() + conversationHistoryTool(history))
            val stream = StreamedAssistant(history, CoroutineScope(currentCoroutineContext()))
            val received = try {
                strategy.chat(visible, systemPrompt, stream::append)
            } finally {
                stream.preservePartial()
            }
            if (received.toolCalls.size > 256) throw RunPausedException("This response proposed more than 256 parallel actions. Split it into smaller batches; no calls were dispatched.")
            if (received.toolCalls.any { it.arguments.length > 1_048_576 }) throw RunPausedException("Tool arguments exceeded the per-call safety size. Split the action into smaller requests; no calls were dispatched.")
            require(received.toolCalls.map { it.id }.distinct().size == received.toolCalls.size) { "Provider returned duplicate tool-call identities; no calls were dispatched" }
            val usedIds = history.value.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
            val result = received.copy(toolCalls = received.toolCalls.map { if (it.id in usedIds) it.copy(id = "call-${Uuid.random()}", providerCallId = it.id) else it })
            val recovery = guard.check(result.toolCalls, history.value)
            if (recovery != null) {
                stream.complete(History(role = History.Role.ASSISTANT, content = result.textContent + "\n[Execution guard: $recovery]"))
                continue
            }
            stream.complete(
                History(role = History.Role.ASSISTANT, content = result.textContent, isThinking = result.isThinkingContent, toolCalls = result.toolCalls.takeIf { it.isNotEmpty() }?.toImmutableList(), reasoningContent = result.reasoningContent, providerBlocks = result.providerBlocks, providerKey = strategy.providerKey, providerUsage = result.providerUsage),
            )
            if (result.toolCalls.isEmpty()) return AssistantTurn(result.textContent, result.reasoningContent?.takeIf { !result.isThinkingContent }, stream.id)
            guard.backoffIfPolling(result.toolCalls)
            val results = executeToolCallsInParallel(result.toolCalls.map { Triple(it.id, it.name, it.arguments) }, history)
            guard.record(result.toolCalls, results)
        }
    }

    /**
     * Executes tool calls in parallel, showing TOOL_EXECUTING indicators in [history] — the run's
     * own flow, so background runs (heartbeat, scheduled tasks) don't leak rows into the open chat.
     * Returns a list of (callId, toolName, result).
     */
    private suspend fun executeToolCallsInParallel(
        toolCalls: List<Triple<String, String, String>>,
        history: MutableStateFlow<List<History>>,
    ): List<Triple<String, String, String>> {
        val conversationIdSnapshot = activeConversationId()
        val slots = Semaphore(4)
        return coroutineScope {
            toolCalls.map { (callId, name, arguments) ->
                async {
                    slots.withPermit {
                        executeRecordedTool(callId, name, arguments, conversationIdSnapshot, history)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun executeRecordedTool(callId: String, name: String, arguments: String, conversationId: String?, history: MutableStateFlow<List<History>>): Triple<String, String, String> {
        currentCoroutineContext().ensureActive()
        val displayName = toolExecutor.getToolDisplayName(name)
        val uncertain = hasUnknownOutcome(history.value, name, arguments)
        val requiresApproval = toolExecutor.requiresApproval(name, arguments) || uncertain
        if (requiresApproval) {
            val run = currentCoroutineContext()[ConversationRun]
            if (run?.interactive != true) throw RunPausedException("Approval is required for $name. Unattended execution paused before dispatch; no permission was inferred from the model or tool output.")
            val approved = !hasDeniedInvocation(history.value, name, arguments, run.id) && approvalController.await(ToolApprovalRequest(callId, run.conversationId, name, arguments, uncertain))
            if (!approved) {
                val result = """{"success":false,"status":"denied","error":"The user declined this exact action. Do not retry it without a new explicit request."}"""
                updateRunHistory(history) { it + History(role = History.Role.TOOL, content = result, toolCallId = callId, toolName = name) }
                return Triple(callId, name, result)
            }
        }
        currentCoroutineContext().ensureActive()
        markToolDispatched(history, callId)
        updateRunHistory(history) { it + History(id = "executing-$callId", role = History.Role.TOOL_EXECUTING, content = name, toolName = displayName) }
        try {
            val permission = if (requiresApproval) ToolExecutionPermit(name, arguments) else kotlin.coroutines.EmptyCoroutineContext
            val result = withContext(permission) {
                toolExecutor.executeTool(name, arguments, conversationId, additionalTools = listOf(conversationHistoryTool(history)))
            }
            // An action that finished just before Stop still needs its receipt committed.
            withContext(NonCancellable) {
                updateRunHistory(history) { entries ->
                    entries.filterNot { it.id == "executing-$callId" } + History(role = History.Role.TOOL, content = result, toolCallId = callId, toolName = name)
                }
            }
            return Triple(callId, name, result)
        } catch (e: RunPausedException) {
            val result = buildJsonObject {
                put("success", false)
                put("status", "not_started")
                put("error", e.message.orEmpty())
            }.toString()
            withContext(NonCancellable) {
                updateRunHistory(history) { it + History(role = History.Role.TOOL, content = result, toolCallId = callId, toolName = name) }
            }
            throw e
        } finally {
            withContext(NonCancellable) { updateRunHistory(history) { entries -> entries.filterNot { it.id == "executing-$callId" } } }
        }
    }

    /**
     * Failures that a retry a second later cannot fix: exhausted credit, rejected credentials, and
     * requests the provider refused as malformed, too large or disallowed. Retrying these only
     * delays the fallback chain (~3 s per service). Unknown/generic errors stay retryable.
     */
    private fun isNonRetryableException(e: Exception): Boolean = when (e) {
        is CodexAuthException,
        is AnthropicInsufficientCreditsException,
        is OpenAICompatibleQuotaExhaustedException,
        is AnthropicInvalidApiKeyException,
        is GeminiInvalidApiKeyException,
        is OpenAICompatibleInvalidApiKeyException,
        is OpenAICompatibleModelNotFoundException,
        is OpenAICompatibleRequestTooLargeException,
        is OpenAICompatibleContentModerationException,
        is OpenAICompatibleBadRequestException,
        is ContextWindowExceededException,
        is UnsupportedFileTypeException,
        is FileTooLargeException,
        -> true

        else -> false
    }

    /**
     * Retries an API call with simple exponential backoff.
     */
    private suspend fun <T> retryApiCall(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0..MAX_API_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isNonRetryableException(e) || e is com.maximiliano.maxit.network.StreamInterruptedException || e is ConversationWriteException || e is RunPausedException) throw e
                lastException = e
                if (attempt < MAX_API_RETRIES) {
                    delay((attempt + 1).seconds)
                }
            }
        }
        throw lastException!!
    }

    private fun estimateMessageChars(msg: com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message): Int {
        val contentChars = when (val content = msg.content) {
            is JsonArray -> {
                // Vision messages: only count text parts, not base64 image data
                content.sumOf { element ->
                    val obj = element as? JsonObject
                    val type = (obj?.get("type") as? JsonPrimitive)?.content
                    if (type == "text") {
                        (obj["text"] as? JsonPrimitive)?.content?.length ?: 0
                    } else {
                        100 // Fixed small cost for image references
                    }
                }
            }

            is JsonPrimitive -> content.content.length

            else -> content?.toString()?.length ?: 0
        }
        return contentChars + msg.role.length
    }

    /**
     * Trims messages to fit within the estimated context window by dropping oldest messages
     * (keeping the system prompt and most recent messages).
     */
    private fun trimMessagesForContext(
        messages: List<com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = messages.sumOf { estimateMessageChars(it) }
        if (totalChars <= maxChars) return messages

        // Keep system prompt (first message if role is "system") and trim from oldest non-system
        val systemMessages = messages.takeWhile { it.role == "system" }
        val nonSystemMessages = messages.drop(systemMessages.size)

        val systemChars = systemMessages.sumOf { estimateMessageChars(it) }
        val availableChars = maxChars - systemChars

        // Group each assistant tool-call turn together with the tool responses that follow it so
        // trimming never strands one without the other. Strict OpenAI-compatible providers (e.g.
        // DeepSeek via OpenCode Zen) reject an assistant `tool_calls` message that isn't followed
        // by its tool responses, and a `tool` message without a preceding `tool_calls`.
        val groups = mutableListOf<List<com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>>()
        var index = 0
        while (index < nonSystemMessages.size) {
            val msg = nonSystemMessages[index]
            if (msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()) {
                var end = index + 1
                while (end < nonSystemMessages.size && nonSystemMessages[end].role == "tool") {
                    end++
                }
                groups.add(nonSystemMessages.subList(index, end).toList())
                index = end
            } else {
                groups.add(listOf(msg))
                index++
            }
        }

        // Keep whole groups from the end until we exceed the budget.
        val kept = mutableListOf<com.maximiliano.maxit.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>()
        var usedChars = 0
        for (group in groups.asReversed()) {
            val groupChars = group.sumOf { estimateMessageChars(it) }
            if (usedChars + groupChars > availableChars) break
            kept.addAll(0, group)
            usedChars += groupChars
        }

        return systemMessages + kept
    }

    private fun persistHistory(conversationId: String, history: List<History>, expectedGeneration: Long? = null, conversationType: String? = null) {
        val now = Clock.System.now().toEpochMilliseconds()

        val existingConversation = savedConversations.value.find { it.id == conversationId }

        val title = existingConversation?.title?.ifEmpty { null }
            ?: deriveTitle(history)
        val conversation = Conversation(
            id = conversationId,
            messages = history
                .filter { it.role != History.Role.TOOL_EXECUTING }
                .map { it.toStoredMessage() },
            createdAt = existingConversation?.createdAt ?: now,
            updatedAt = now,
            title = title,
            type = existingConversation?.type ?: conversationType ?: if (interactiveModeFlag) Conversation.TYPE_INTERACTIVE else Conversation.TYPE_CHAT,
        )

        conversationStorage.saveConversation(conversation, expectedGeneration)
    }

    override fun clearHistory() = startNewChat()

    override fun isUsingSharedKey(): Boolean = false

    override fun supportedFileExtensions(): List<String> {
        val service = currentService()
        if (service.isOnDevice) return emptyList()
        // Images are offered only when both the service and the active model accept them —
        // mixed services (e.g. Z.AI) pair text-only models with multimodal ones.
        val imagesSupported = service.supportsImages && modelSupportsImages(currentModelId())
        val base = if (imagesSupported) supportedFileExtensions else supportedFileExtensions - imageExtensions
        return if (service.supportsPdf) base + "pdf" else base
    }

    override fun currentService(): Service {
        val instances = getConfiguredServiceInstances()
        return instances.firstOrNull()?.let { Service.fromId(it.serviceId) } ?: Service.Free
    }

    /** Model id of the active (first configured, non-Free) instance, or "" when none. */
    private fun currentModelId(): String {
        val instance = getConfiguredServiceInstances().firstOrNull() ?: return ""
        val service = Service.fromId(instance.serviceId)
        return appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
    }

    private fun setCurrentConversationId(id: String?) {
        appSettings.setCurrentConversationId(id)
        selectedStorageGeneration = conversationStorage.generation()
        _currentConversationId.value = id
    }

    // Conversation management
    override fun loadConversations() {
        conversationStorage.loadConversations()
        selectionLock.locked {
            _currentConversationId.value?.let { id ->
                val selected = savedConversations.value.find { it.id == id }
                if (selected == null) setCurrentConversationId(null)
                selectedStorageGeneration = conversationStorage.generation()
                chatHistory.value = selected?.messages?.map { it.toHistory() }.orEmpty()
            }
        }
        val latestStatus = savedConversations.value.firstOrNull { it.type == Conversation.TYPE_HEARTBEAT }?.messages?.lastOrNull { it.isStatusMessage }?.state
        if (latestStatus in setOf(MessageState.PAUSED, MessageState.INTERRUPTED, MessageState.FAILED)) _hasUnreadHeartbeat.value = true
    }

    override fun loadConversation(id: String) = selectionLock.locked {
        val conversation = savedConversations.value.find { it.id == id }
        if (conversation != null) {
            setCurrentConversationId(id)
            chatHistory.value = conversation.messages.map { it.toHistory() }
        }
    }

    override suspend fun deleteConversation(id: String) {
        conversationStorage.deleteConversation(id)
        selectionLock.locked {
            if (_currentConversationId.value == id) {
                setCurrentConversationId(null)
                chatHistory.value = emptyList()
            }
        }
        // Drop the per-conversation shell session so a future conversation reusing
        // this id (very unlikely — random uuids) doesn't inherit stale state, and
        // memory is freed.
        sandboxController.closeSession(id)
    }

    override fun startNewChat() = selectionLock.locked {
        setCurrentConversationId(null)
        chatHistory.value = emptyList()
    }

    override suspend fun popLastExchange() = supersedeFrom { history -> history.indexOfLast { it.role == History.Role.USER && !it.superseded }.takeIf { it >= 0 } ?: history.size }

    override suspend fun truncateFrom(messageId: String) = supersedeFrom { history -> history.indexOfFirst { it.id == messageId }.takeIf { it >= 0 } ?: history.size }

    private suspend fun supersedeFrom(cutoff: (List<History>) -> Int) {
        val (id, generation, anchor) = selectionLock.locked {
            val current = chatHistory.value
            Triple(_currentConversationId.value, selectedStorageGeneration, current.getOrNull(cutoff(current))?.id)
        }
        if (id == null || anchor == null) return
        agentMutex.withLock {
            conversationStorage.loadConversations()
            if (generation != conversationStorage.generation()) throw CancellationException("Conversation history was replaced. Reopen the saved conversation before continuing.")
            val current = savedConversations.value.find { it.id == id }?.messages?.map { it.toHistory() }
                ?: throw CancellationException("Conversation was deleted before its history could be changed")
            val index = current.indexOfFirst { it.id == anchor }
            check(index >= 0) { "The message to replace is no longer available" }
            val next = current.mapIndexed { i, entry -> if (i >= index) entry.copy(superseded = true) else entry }
            persistHistory(id, next, generation)
            selectionLock.locked { if (_currentConversationId.value == id) chatHistory.value = next }
        }
    }

    override fun restoreCurrentConversation() {
        // One-time migration for existing users: pin the latest conversation as the new
        // "current" pointer so the upgrade is non-disruptive.
        if (!appSettings.isCurrentConversationMigrated()) {
            val latest = savedConversations.value.maxByOrNull { it.updatedAt }
            if (latest != null) {
                loadConversation(latest.id)
            }
            appSettings.markCurrentConversationMigrated()
            return
        }

        // Already-loaded guard (covers re-entry from refreshSettings)
        val currentId = _currentConversationId.value
        if (currentId != null && chatHistory.value.isNotEmpty() &&
            savedConversations.value.any { it.id == currentId }
        ) {
            return
        }

        val persistedId = appSettings.getCurrentConversationId()
        if (persistedId != null && savedConversations.value.any { it.id == persistedId }) {
            loadConversation(persistedId)
        }
        // else: null id or stale id → leave history empty (this is the new-empty-chat state)
    }

    // Tool management
    override fun getToolDefinitions(): List<ToolInfo> = getPlatformToolDefinitions()
        .filter { it.userToggleable }
        .map { it.copy(isEnabled = appSettings.isToolEnabled(it.id, defaultEnabled = it.isEnabled)) }

    override fun setToolEnabled(toolId: String, enabled: Boolean) {
        appSettings.setToolEnabled(toolId, enabled)
    }

    // MCP servers
    override fun getMcpServers(): List<McpServerConfig> = mcpServerManager.getServers()

    override suspend fun addMcpServer(name: String, url: String, headers: Map<String, String>): McpServerConfig = mcpServerManager.addServer(name, url, headers)

    override fun removeMcpServer(serverId: String) {
        mcpServerManager.removeServer(serverId)
    }

    override fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        mcpServerManager.setServerEnabled(serverId, enabled)
    }

    override suspend fun connectMcpServer(serverId: String): Result<List<ToolInfo>> {
        val result = mcpServerManager.connectAndDiscoverTools(serverId)
        return result.map { mcpServerManager.getToolsForServer(serverId) }
    }

    override fun getMcpToolsForServer(serverId: String): List<ToolInfo> = mcpServerManager.getToolsForServer(serverId)

    override fun isMcpServerConnected(serverId: String): Boolean = mcpServerManager.isConnected(serverId)

    override suspend fun connectEnabledMcpServers() {
        mcpServerManager.connectEnabledServers()
    }

    // Skills
    override val installedSkillsFlow: StateFlow<List<SkillManifest>> = skillManager.skills
    override fun getInstalledSkills(): List<SkillManifest> = skillManager.getInstalled()

    override suspend fun uninstallSkill(id: String) {
        skillManager.uninstall(id)
    }

    override suspend fun importSkill(file: PlatformFile): Result<SkillManifest> = skillManager.importSkill(file)

    override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> = skillManager.installFromGitHub(owner, repo, ref, path)

    // Agent instructions (legacy storage/API names retained for compatibility).
    override fun getSoulText(): String = appSettings.getSoulText()

    override fun setSoulText(text: String) {
        appSettings.setSoulText(text)
    }

    override suspend fun getActiveSystemPrompt(variant: SystemPromptVariant): String? = buildActiveSystemPrompt(variant, currentService(), currentModelId())

    private suspend fun buildActiveSystemPrompt(
        variant: SystemPromptVariant,
        service: Service,
        modelId: String,
        toolsEnabled: Boolean = true,
    ): String? {
        val soul = loadAgentInstructions(appSettings.getSoulText())
        val memoryEnabled = appSettings.isMemoryEnabled()
        val schedulingEnabled = appSettings.isSchedulingEnabled()

        val memoryInstructions = if (memoryEnabled) {
            appSettings.getMemoryInstructions().ifEmpty { null }
        } else {
            null
        }

        val memories = if (memoryEnabled) memoryStore.getAllMemories() else emptyList()
        val byCategory = memories.groupBy { it.category }

        val tasksSplit = if (schedulingEnabled) taskStore.getPendingTasksPartitioned() else PendingTaskPartition(emptyList(), emptyList())
        val pendingTasks = tasksSplit.scheduled
        val heartbeatAdditions = tasksSplit.heartbeatAdditions

        // Surface connected email accounts so the AI knows they exist in regular chat,
        // not just during heartbeats. Only the remote variant uses this — email tools
        // aren't in the local allowlist. Gated on the email toggle: if the user has email
        // off, the AI shouldn't reference the accounts.
        val emailAccounts = if (variant == SystemPromptVariant.CHAT_REMOTE && appSettings.isEmailEnabled()) {
            emailStore.getAccounts().map { account ->
                val state = emailStore.getSyncState(account.id)
                EmailAccountSummary(
                    email = account.email,
                    unreadCount = state.unreadCount,
                    lastSyncEpochMs = state.lastSyncEpochMs,
                    lastError = state.lastError,
                )
            }
        } else {
            emptyList()
        }

        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val localDateTime = now.toLocalDateTime(timeZone)
        val offset = timeZone.offsetAt(now)
        val runtime = ChatPromptRuntimeContext(
            nowLocalIsoWithOffset = "$localDateTime$offset",
            timeZoneId = timeZone.id,
            nowUtcIsoString = now.toString(),
            platform = currentPlatform.displayName,
            modelId = modelId,
            providerName = service.displayName,
        )

        // Tool-use guidance is only worth sending when the model is actually given tools.
        // Mirror the tool list the request will carry: remote uses the full set (when the
        // model supports tools), local uses the allowlist-filtered set.
        val availableTools = when {
            !toolsEnabled -> emptyList()
            variant == SystemPromptVariant.CHAT_REMOTE -> if (supportsTools(modelId)) getAvailableTools() else emptyList()
            localModelDeclaresTools(modelId) -> getLocalSafeTools()
            else -> emptyList()
        }

        val activeSkill = pendingActiveSkillId?.let { skillManager.getSkill(it) }

        return buildChatSystemPrompt(
            variant = variant,
            soul = soul,
            hasTools = availableTools.isNotEmpty(),
            memoryEnabled = memoryEnabled,
            schedulingEnabled = schedulingEnabled,
            memoryInstructions = memoryInstructions,
            generalMemories = byCategory[MemoryCategory.GENERAL].orEmpty(),
            preferenceMemories = byCategory[MemoryCategory.PREFERENCE].orEmpty(),
            learningMemories = byCategory[MemoryCategory.LEARNING].orEmpty(),
            errorMemories = byCategory[MemoryCategory.ERROR].orEmpty(),
            pendingTasks = pendingTasks,
            heartbeatAdditions = heartbeatAdditions,
            emailAccounts = emailAccounts,
            runtime = runtime,
            activeSkill = activeSkill,
            installedSkills = if (availableTools.any { it.schema.name == SkillTools.ID }) skillManager.getInstalled().filter { it.loadError == null } else emptyList(),
        ).ifEmpty { null }
    }

    override fun isDynamicUiEnabled(): Boolean = appSettings.isDynamicUiEnabled()

    override fun setDynamicUiEnabled(enabled: Boolean) {
        appSettings.setDynamicUiEnabled(enabled)
    }

    override fun getThemeMode(): ThemeMode = appSettings.getThemeMode()

    override fun setThemeMode(mode: ThemeMode) {
        appSettings.setThemeMode(mode)
    }

    private var interactiveModeFlag = appSettings.getCurrentInteractiveMode()

    override fun setInteractiveMode(enabled: Boolean) {
        interactiveModeFlag = enabled
        appSettings.setCurrentInteractiveMode(enabled)
    }

    override fun isInteractiveModeActive(): Boolean = interactiveModeFlag

    override fun isMemoryEnabled(): Boolean = appSettings.isMemoryEnabled()

    override fun setMemoryEnabled(enabled: Boolean) {
        appSettings.setMemoryEnabled(enabled)
    }

    override fun getMemories(): List<MemoryEntry> = memoryStore.getAllMemories()

    override suspend fun deleteMemory(key: String) {
        memoryStore.forget(key)
    }

    override suspend fun updateMemoryContent(key: String, content: String) {
        memoryStore.updateContent(key, content)
    }

    override fun isSchedulingEnabled(): Boolean = appSettings.isSchedulingEnabled()

    override fun setSchedulingEnabled(enabled: Boolean) {
        appSettings.setSchedulingEnabled(enabled)
    }

    override fun getScheduledTasks(): List<ScheduledTask> = taskStore.getAllTasks()

    override suspend fun resumeScheduledTask(id: String) = taskStore.resumeTask(id)

    override suspend fun cancelScheduledTask(id: String) {
        taskStore.removeTask(id)
    }

    override fun isDaemonEnabled(): Boolean = appSettings.isDaemonEnabled()

    override fun setDaemonEnabled(enabled: Boolean) {
        appSettings.setDaemonEnabled(enabled)
    }

    override fun isSandboxEnabled(): Boolean = appSettings.isSandboxEnabled()

    override fun setSandboxEnabled(enabled: Boolean) {
        appSettings.setSandboxEnabled(enabled)
    }

    override fun getSandboxDistro(): LinuxDistro = appSettings.getSandboxDistro()

    override fun setSandboxDistro(distro: LinuxDistro) {
        appSettings.setSandboxDistro(distro)
    }

    override fun getKaiBuildLaunchAgent(): String? = appSettings.getKaiBuildLaunchAgent()

    override fun setKaiBuildLaunchAgent(agentId: String?) {
        appSettings.setKaiBuildLaunchAgent(agentId)
    }

    override fun getHeartbeatConfig(): HeartbeatConfig = heartbeatManager.getConfig()

    override fun setHeartbeatEnabled(enabled: Boolean) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(enabled = enabled))
    }

    override fun setHeartbeatIntervalMinutes(minutes: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(intervalMinutes = minutes))
    }

    override fun setHeartbeatActiveHours(start: Int, end: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(activeHoursStart = start, activeHoursEnd = end))
    }

    override fun getHeartbeatPrompt(): String = appSettings.getHeartbeatPrompt()

    override fun setHeartbeatPrompt(text: String) {
        appSettings.setHeartbeatPrompt(text)
    }

    override fun getHeartbeatLog(): List<HeartbeatLogEntry> = heartbeatManager.getHeartbeatLog()

    override fun getHeartbeatInstanceId(): String? = heartbeatManager.getConfig().heartbeatInstanceId

    override fun setHeartbeatInstanceId(instanceId: String?) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(heartbeatInstanceId = instanceId))
    }

    override fun isEmailEnabled(): Boolean = appSettings.isEmailEnabled()

    override fun setEmailEnabled(enabled: Boolean) {
        appSettings.setEmailEnabled(enabled)
    }

    override fun getEmailAccounts(): List<EmailAccount> = emailStore.getAccounts()

    override suspend fun removeEmailAccount(id: String) {
        emailStore.removeAccount(id)
    }

    override fun getEmailPollIntervalMinutes(): Int = appSettings.getEmailPollIntervalMinutes()

    override fun getPendingEmailCount(): Int = emailStore.getPending().size

    override fun getEmailSyncStates(): Map<String, EmailSyncState> = emailStore.getAllSyncStates()

    override suspend fun pollEmailAccount(accountId: String) {
        val account = emailStore.getAccount(accountId) ?: return
        emailPoller.poll(account)
    }

    override fun setEmailPollIntervalMinutes(minutes: Int) {
        appSettings.setEmailPollIntervalMinutes(minutes)
    }

    override fun isSmsEnabled(): Boolean = appSettings.isSmsEnabled()

    override fun setSmsEnabled(enabled: Boolean) {
        appSettings.setSmsEnabled(enabled)
    }

    override fun getSmsPollIntervalMinutes(): Int = appSettings.getSmsPollIntervalMinutes()

    override fun setSmsPollIntervalMinutes(minutes: Int) {
        appSettings.setSmsPollIntervalMinutes(minutes)
    }

    override fun getPendingSmsCount(): Int = smsStore.getPending().size

    override fun getSmsSyncState(): SmsSyncState = smsStore.getSyncState()

    override fun hasSmsPermission(): Boolean = smsReader.hasPermission()

    override suspend fun requestSmsPermission(): Boolean = smsPermissionController.requestPermission()

    override suspend fun pollSms() {
        smsPoller.poll()
    }

    override fun isSmsSendEnabled(): Boolean = appSettings.isSmsSendEnabled()

    override fun setSmsSendEnabled(enabled: Boolean) {
        appSettings.setSmsSendEnabled(enabled)
    }

    override fun hasSmsSendPermission(): Boolean = smsSender.hasPermission()

    override suspend fun requestSmsSendPermission(): Boolean = smsSendPermissionController.requestPermission()

    override val smsDrafts: StateFlow<List<SmsDraft>> = smsDraftStore.drafts

    // Flips the draft to SENDING, delegates to SmsSender, then updates to SENT/FAILED.
    // Explicit user-triggered (never AI-triggered) — the banner is the gate.
    override suspend fun sendSmsDraft(draftId: String): Boolean {
        val draft = smsDraftStore.getDraft(draftId) ?: return false
        if (draft.status != SmsDraftStatus.PENDING) return false
        smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENDING)
        return when (val result = smsSender.send(draft.address, draft.body)) {
            is SmsSendResult.Success -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENT)
                true
            }

            is SmsSendResult.Failure -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.FAILED, result.message)
                false
            }
        }
    }

    override suspend fun discardSmsDraft(draftId: String) {
        smsDraftStore.removeDraft(draftId)
    }

    override fun isNotificationsEnabled(): Boolean = appSettings.isNotificationsEnabled()

    override fun setNotificationsEnabled(enabled: Boolean) {
        appSettings.setNotificationsEnabled(enabled)
    }

    override fun isNotificationListenerAccessGranted(): Boolean = notificationListenerController.isAccessGranted()

    override fun openNotificationListenerSettings() {
        notificationListenerController.openAccessSettings()
    }

    override fun getPendingNotificationCount(): Int = notificationStore.getPending().size

    override fun getNotificationSyncState(): NotificationSyncState = notificationStore.getSyncState()

    override suspend fun clearPendingNotifications() {
        notificationStore.clearPending()
    }

    override fun getUiScale(): Float = appSettings.getUiScale()

    override fun setUiScale(scale: Float) {
        appSettings.setUiScale(scale)
    }

    override fun exportSettingsToJson(sections: Set<ImportSection>): String {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, sections, conversationStorage.conversations.value)
        return prettyJson.encodeToString(JsonObject.serializer(), jsonObject)
    }

    override fun getExportPreview(): Map<ImportSection, String?> {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, conversations = conversationStorage.conversations.value)
        return detectExportableSections(jsonObject)
    }

    override fun importSettingsFromJson(json: String, sections: Set<ImportSection>, replace: Boolean): Int {
        val jsonObject = SharedJson.parseToJsonElement(json).jsonObject
        val toolIds = getPlatformToolDefinitions().map { it.id }
        if (replace || ImportSection.SERVICES in sections) {
            getConfiguredServiceInstances().forEach { codexAuth.signOut(it.instanceId) }
            modelsByInstance.clear()
        }
        return appSettings.importFromJson(jsonObject, toolIds, sections, replace)
    }

    override suspend fun askWithTools(prompt: String, instanceId: String?, conversationIdOverride: String?): String {
        // Selection: explicit instance > first remote > first on-device. The simple-tool
        // allowlist works at any context size, so on-device is always eligible for fallback.
        val targetInstance = selectBackgroundServiceInstance(getConfiguredServiceInstances(), instanceId, preferRemote = true) {
            hasValidInstanceApiKey(it.instanceId, Service.fromId(it.serviceId))
        }
            ?: throw GenericNetworkException("No usable model provider. Sign in with ChatGPT or configure a provider in Settings.")
        val service = Service.fromId(targetInstance.serviceId)
        val messages = listOf(History(role = History.Role.USER, content = prompt))
        return agentMutex.withLock {
            conversationStorage.loadConversations()
            val id = conversationIdOverride ?: getOrCreateHeartbeatConversationId()
            val initial = savedConversations.value.find { it.id == id }?.messages.orEmpty().map { it.toHistory() }
            val run = newConversationRun(id, initial)
            withContext(run + ConversationIdElement(id)) {
                try {
                    run.update { it + messages + History(role = History.Role.ASSISTANT, content = "", isStatusMessage = true, state = MessageState.RUNNING) }
                    val turn = askWithService(service, run.history.value, targetInstance.instanceId, run.history)
                    if (turn.messageId == null) run.update { it + History(role = History.Role.ASSISTANT, content = turn.content, reasoningContent = turn.reasoningContent) }
                    run.finish(MessageState.COMPLETE, "")
                    if (savedConversations.value.any { it.id == id && it.type == Conversation.TYPE_HEARTBEAT } && turn.content.isNotBlank() && "HEARTBEAT_OK" !in turn.content) _hasUnreadHeartbeat.value = true
                    turn.content
                } catch (e: RunPausedException) {
                    run.finish(MessageState.PAUSED, e.message.orEmpty())
                    throw e
                } catch (e: CancellationException) {
                    run.finish(MessageState.INTERRUPTED, "Background execution was interrupted. Verify unknown action outcomes; this attempt must not be replayed automatically.")
                    throw e
                } catch (e: Exception) {
                    run.finish(MessageState.FAILED, "Background execution did not finish. Saved actions and partial output are preserved.")
                    if (run.history.value.any { it.runId == run.id && !it.isStatusMessage && it.role == History.Role.ASSISTANT }) throw RunPausedException("This attempt already recorded assistant output or proposed actions. Review saved activity before starting it again.")
                    throw e
                }
            }
        }
    }

    override suspend fun askSilently(question: String): String {
        val firstInstance = selectBackgroundServiceInstance(getConfiguredServiceInstances()) {
            hasValidInstanceApiKey(it.instanceId, Service.fromId(it.serviceId))
        } ?: throw GenericNetworkException("No usable model provider. Sign in with ChatGPT or configure a provider in Settings.")
        val service = Service.fromId(firstInstance.serviceId)
        val messages = listOf(History(role = History.Role.USER, content = question))

        if (service.isOnDevice) {
            // Throwaway history — we don't want tool-execution rows leaking into the
            // visible chatHistory for a "silent" call. LOCAL variant of the system
            // prompt so small on-device models get the right section set.
            val localPrompt = buildActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL, service, appSettings.getInstanceModelId(firstInstance.instanceId))
            return askWithLocalEngine(messages, localPrompt, firstInstance.instanceId, MutableStateFlow(messages), allowTools = false) { modelId ->
                buildActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL, service, modelId, toolsEnabled = false)
            }
        }

        val creds = instanceCredentials(firstInstance.instanceId, service)
        // plainChat has no tool loop: do not advertise skills it cannot load.
        val systemPrompt = buildActiveSystemPrompt(SystemPromptVariant.CHAT_REMOTE, service, creds.modelId, toolsEnabled = false)

        return plainChat(service, creds, messages, systemPrompt, retry = false).content
    }

    private val _hasUnreadHeartbeat = MutableStateFlow(false)
    override val hasUnreadHeartbeat: StateFlow<Boolean> = _hasUnreadHeartbeat

    override fun clearUnreadHeartbeat() {
        _hasUnreadHeartbeat.value = false
    }

    private val _openHeartbeatRequested = MutableStateFlow(false)
    override val openHeartbeatRequested: StateFlow<Boolean> = _openHeartbeatRequested

    override fun requestOpenHeartbeat() {
        _openHeartbeatRequested.value = true
    }

    override fun consumeOpenHeartbeatRequest() {
        _openHeartbeatRequested.value = false
    }

    private val _openAssistRequested = MutableStateFlow(false)
    override val openAssistRequested: StateFlow<Boolean> = _openAssistRequested

    override fun requestOpenAssist() {
        _openAssistRequested.value = true
    }

    override fun consumeOpenAssistRequest() {
        _openAssistRequested.value = false
    }

    private val _pendingShareText = MutableStateFlow<String?>(null)
    override val pendingShareText: StateFlow<String?> = _pendingShareText

    override fun requestOpenShare(text: String) {
        _pendingShareText.value = text
    }

    override fun consumeOpenShareRequest() {
        _pendingShareText.value = null
    }

    override suspend fun getOrCreateHeartbeatConversationId(): String = heartbeatMutex.withLock {
        conversationStorage.loadConversations()
        val existing = savedConversations.value.find { it.type == Conversation.TYPE_HEARTBEAT }
        if (existing != null) return@withLock existing.id
        val now = Clock.System.now().toEpochMilliseconds()
        val id = Uuid.random().toString()
        conversationStorage.saveConversation(
            Conversation(
                id = id,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now,
                type = Conversation.TYPE_HEARTBEAT,
            ),
        )
        id
    }

    private fun deriveTitle(history: List<History>): String {
        val firstUserMessage = history.firstOrNull { it.role == History.Role.USER }?.content ?: return ""
        return if (firstUserMessage.length <= 50) {
            firstUserMessage
        } else {
            val truncated = firstUserMessage.take(50)
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 20) truncated.substring(0, lastSpace) + "..." else truncated + "..."
        }
    }

    // On-device inference (LiteRT)

    override fun isLocalInferenceAvailable(): Boolean = localInferenceEngine != null

    override fun getLocalEngineState(): StateFlow<EngineState>? = localInferenceEngine?.engineState

    override fun getLocalDownloadingModelId(): StateFlow<String?>? = localInferenceEngine?.downloadingModelId

    override fun getLocalDownloadProgress(): StateFlow<Float?>? = localInferenceEngine?.downloadProgress

    override fun getLocalDownloadError(): StateFlow<DownloadError?>? = localInferenceEngine?.downloadError

    override fun getLocalDownloadedModels(): List<DownloadedModel> = localInferenceEngine?.getDownloadedModels() ?: emptyList()

    override fun getLocalAvailableModels(): List<LocalModel> = localInferenceEngine?.getAvailableModels() ?: emptyList()

    override fun getLocalImportedModels(): List<LocalModel> = localInferenceEngine?.getImportedLocalModels() ?: emptyList()

    override fun getLocalFreeSpaceBytes(): Long = localInferenceEngine?.getFreeSpaceBytes() ?: 0L

    override fun getTotalDeviceMemoryBytes(): Long = getTotalMemoryBytes()

    override fun getModelContextTokens(modelId: String): Int = appSettings.getModelContextTokens(modelId)

    override fun setModelContextTokens(modelId: String, contextTokens: Int) {
        appSettings.setModelContextTokens(modelId, contextTokens)
    }

    override suspend fun releaseLocalEngine() {
        localInferenceEngine?.release()
    }

    override fun getLocalImportingFileName(): StateFlow<String?>? = localInferenceEngine?.importingFileName

    override fun getLocalImportProgress(): StateFlow<Float?>? = localInferenceEngine?.importProgress

    override fun getLocalImportError(): StateFlow<ModelImportError?>? = localInferenceEngine?.importError

    override fun startLocalModelDownload(model: LocalModel) {
        localInferenceEngine?.startDownload(model)
    }

    override fun cancelLocalModelDownload() {
        localInferenceEngine?.cancelDownload()
    }

    override suspend fun importLocalModel(source: PlatformFile): ModelImportResult {
        val engine = localInferenceEngine
            ?: return ModelImportResult.Failure(ModelImportError.COPY_FAILED, "On-device inference not available")
        return engine.importModel(source)
    }

    override fun cancelLocalModelImport() {
        localInferenceEngine?.cancelImport()
    }

    override suspend fun deleteLocalModel(modelId: String) {
        localInferenceEngine?.deleteModel(modelId)
    }
}
