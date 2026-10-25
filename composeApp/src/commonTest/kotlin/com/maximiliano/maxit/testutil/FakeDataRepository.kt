// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.testutil

import com.maximiliano.maxit.data.Conversation
import com.maximiliano.maxit.data.ConversationInputAccepted
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.EmailAccount
import com.maximiliano.maxit.data.EmailSyncState
import com.maximiliano.maxit.data.FallbackStatus
import com.maximiliano.maxit.data.FreeMode
import com.maximiliano.maxit.data.HeartbeatConfig
import com.maximiliano.maxit.data.HeartbeatLogEntry
import com.maximiliano.maxit.data.ImportSection
import com.maximiliano.maxit.data.MemoryEntry
import com.maximiliano.maxit.data.ScheduledTask
import com.maximiliano.maxit.data.Service
import com.maximiliano.maxit.data.ServiceEntry
import com.maximiliano.maxit.data.ServiceInstance
import com.maximiliano.maxit.data.SmsDraft
import com.maximiliano.maxit.data.SmsSyncState
import com.maximiliano.maxit.data.SystemPromptVariant
import com.maximiliano.maxit.data.ThemeMode
import com.maximiliano.maxit.data.ensureSelectedModelPresent
import com.maximiliano.maxit.inference.DownloadError
import com.maximiliano.maxit.inference.DownloadedModel
import com.maximiliano.maxit.inference.EngineState
import com.maximiliano.maxit.inference.LocalModel
import com.maximiliano.maxit.inference.ModelImportError
import com.maximiliano.maxit.inference.ModelImportResult
import com.maximiliano.maxit.linux.LinuxDistro
import com.maximiliano.maxit.mcp.McpServerConfig
import com.maximiliano.maxit.network.tools.ToolInfo
import com.maximiliano.maxit.tools.CommonTools
import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.settings.SettingsModel
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class FakeDataRepository : DataRepository {

    private var currentService: Service = Service.Free

    override val chatHistory: MutableStateFlow<List<History>> = MutableStateFlow(emptyList())
    override val currentConversationId: MutableStateFlow<String?> = MutableStateFlow(null)
    override val fallbackStatus: MutableStateFlow<FallbackStatus?> = MutableStateFlow(null)

    val askCalls = mutableListOf<Pair<String?, List<PlatformFile>>>()
    var clearHistoryCalls = 0
    var askException: Exception? = null

    /**
     * When non-null, [ask] suspends on this gate before doing any work. Tests can use this
     * to keep an in-flight ask in progress while inspecting state (e.g., to verify
     * concurrent ask prevention or to test cancel behavior).
     */
    var askGate: CompletableDeferred<Unit>? = null
    var regenerateCalls = 0

    fun setCurrentService(service: Service) {
        currentService = service
    }

    // Configured services management (instance-based)
    private val configuredInstances = mutableListOf<ServiceInstance>()
    private val instanceApiKeys = mutableMapOf<String, String>()
    private val instanceBaseUrls = mutableMapOf<String, String>()
    private val instanceModels = mutableMapOf<String, MutableStateFlow<List<SettingsModel>>>()
    private var instanceCounter = 0

    override fun getConfiguredServiceInstances(): List<ServiceInstance> = configuredInstances.toList()

    override fun addConfiguredService(serviceId: String): ServiceInstance {
        val existingIds = configuredInstances.map { it.instanceId }.toSet()
        val instanceId = if (serviceId !in existingIds) {
            serviceId
        } else {
            var counter = 2
            while ("${serviceId}_$counter" in existingIds) counter++
            "${serviceId}_$counter"
        }
        val instance = ServiceInstance(instanceId = instanceId, serviceId = serviceId)
        configuredInstances.add(instance)
        freeServicePrimary = false
        return instance
    }

    override fun removeConfiguredService(instanceId: String) {
        configuredInstances.removeAll { it.instanceId == instanceId }
        instanceApiKeys.remove(instanceId)
        instanceBaseUrls.remove(instanceId)
        instanceModels.remove(instanceId)
    }

    override fun reorderConfiguredServices(orderedInstanceIds: List<String>) {
        val byId = configuredInstances.associateBy { it.instanceId }
        val reordered = orderedInstanceIds.mapNotNull { byId[it] }
        configuredInstances.clear()
        configuredInstances.addAll(reordered)
    }

    var fakeServiceEntries: List<ServiceEntry> = emptyList()
    override fun getServiceEntries(): List<ServiceEntry> = fakeServiceEntries

    private var freeFallbackEnabled = true

    override fun isFreeFallbackEnabled(): Boolean = freeFallbackEnabled

    override fun setFreeFallbackEnabled(enabled: Boolean) {
        freeFallbackEnabled = enabled
    }

    private var freeMode = FreeMode.FAST

    override fun getFreeMode(): FreeMode = freeMode

    override fun setFreeMode(mode: FreeMode) {
        freeMode = mode
    }

    private var freeServicePrimary = false

    override fun isFreeServicePrimary(): Boolean = freeServicePrimary

    override fun setFreeServicePrimary(primary: Boolean) {
        freeServicePrimary = primary
    }

    // Per-instance settings
    override fun getInstanceApiKey(instanceId: String): String = instanceApiKeys[instanceId] ?: ""

    override fun updateInstanceApiKey(instanceId: String, apiKey: String) {
        instanceApiKeys[instanceId] = apiKey
    }

    override fun getInstanceBaseUrl(instanceId: String, service: Service): String = instanceBaseUrls[instanceId] ?: if (service is Service.OpenAICompatible) Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL else ""

    override fun updateInstanceBaseUrl(instanceId: String, baseUrl: String) {
        instanceBaseUrls[instanceId] = baseUrl
    }

    override fun getInstanceModels(instanceId: String, service: Service): StateFlow<List<SettingsModel>> = instanceModels.getOrPut(instanceId) {
        MutableStateFlow(
            service.defaultModels.map {
                SettingsModel(
                    id = it.id,
                    subtitle = it.subtitle,
                    descriptionRes = it.descriptionRes,
                )
            },
        )
    }

    override fun updateInstanceSelectedModel(instanceId: String, service: Service, modelId: String) {
        instanceModels[instanceId]?.update { models ->
            ensureSelectedModelPresent(models, modelId)
        }
    }

    private val instanceUseCustomModel = mutableMapOf<String, Boolean>()
    private val instanceCustomModelIds = mutableMapOf<String, String>()

    override fun getInstanceUseCustomModel(instanceId: String): Boolean = instanceUseCustomModel[instanceId] ?: false

    override fun updateInstanceUseCustomModel(instanceId: String, useCustom: Boolean) {
        if (useCustom && instanceCustomModelIds[instanceId].isNullOrBlank()) {
            // Prefill is handled by callers via updateInstanceCustomModelId in production;
            // tests rarely need it — leave blank unless set.
        }
        instanceUseCustomModel[instanceId] = useCustom
    }

    override fun getInstanceCustomModelId(instanceId: String): String = instanceCustomModelIds[instanceId] ?: ""

    override fun updateInstanceCustomModelId(instanceId: String, modelId: String) {
        instanceCustomModelIds[instanceId] = modelId
    }

    override fun clearInstanceModels(instanceId: String, service: Service) {
        instanceModels[instanceId]?.value = emptyList()
    }

    override suspend fun validateConnection(service: Service, instanceId: String) {
        // No-op in tests
    }

    override fun isCodexSignedIn(instanceId: String): Boolean = false

    override suspend fun beginCodexSignIn(instanceId: String): com.maximiliano.maxit.network.CodexDeviceAuthorization = error("No fake Codex authorization configured")

    override suspend fun completeCodexSignIn(instanceId: String, authorization: com.maximiliano.maxit.network.CodexDeviceAuthorization) = Unit

    override fun signOutCodex(instanceId: String) = Unit

    fun setConfiguredServices(vararg services: Service) {
        configuredInstances.clear()
        val usedIds = mutableSetOf<String>()
        for (service in services) {
            val instanceId = if (service.id !in usedIds) {
                service.id
            } else {
                var counter = 2
                while ("${service.id}_$counter" in usedIds) counter++
                "${service.id}_$counter"
            }
            usedIds.add(instanceId)
            configuredInstances.add(ServiceInstance(instanceId = instanceId, serviceId = service.id))
        }
    }

    fun setInstanceApiKey(instanceId: String, apiKey: String) {
        instanceApiKeys[instanceId] = apiKey
    }

    fun setInstanceModels(instanceId: String, models: List<SettingsModel>) {
        instanceModels.getOrPut(instanceId) { MutableStateFlow(emptyList()) }.value = models
    }

    override suspend fun ask(
        question: String?,
        files: List<PlatformFile>,
        uiSubmission: com.maximiliano.maxit.data.UiSubmission?,
        activeSkillId: String?,
    ): History? {
        askCalls.add(question to files)
        lastActiveSkillId = activeSkillId
        askGate?.await()
        askException?.let { throw it }
        if (question != null) {
            chatHistory.update { history ->
                history + History(role = History.Role.USER, content = question, uiSubmission = uiSubmission)
            }
            kotlin.coroutines.coroutineContext[ConversationInputAccepted]?.notify?.invoke()
        }
        val answer = History(role = History.Role.ASSISTANT, content = "Test response")
        chatHistory.update { it + answer }
        return answer
    }

    override fun clearHistory() {
        clearHistoryCalls++
        chatHistory.value = emptyList()
    }

    override fun currentService(): Service = currentService

    override fun isUsingSharedKey(): Boolean = currentService == Service.Free
    override fun supportedFileExtensions(): List<String> = if (fileAttachmentSupported) listOf("txt", "pdf", "png") else emptyList()

    var fileAttachmentSupported = true

    // Conversation management
    override val savedConversations: MutableStateFlow<List<Conversation>> = MutableStateFlow(emptyList())

    override fun loadConversations() {
        // No-op in tests
    }

    override fun loadConversation(id: String) {
        val conversation = savedConversations.value.find { it.id == id } ?: return
        currentConversationId.value = id
        chatHistory.value = conversation.messages.map { m ->
            History(
                id = m.id,
                role = when (m.role) {
                    "user" -> History.Role.USER
                    "tool" -> History.Role.TOOL
                    else -> History.Role.ASSISTANT
                },
                content = m.content,
            )
        }
    }

    override suspend fun deleteConversation(id: String) {
        if (currentConversationId.value == id) {
            currentConversationId.value = null
            chatHistory.value = emptyList()
        }
        savedConversations.update { it.filter { c -> c.id != id } }
    }

    override suspend fun regenerate(): History? {
        regenerateCalls++
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER && !it.superseded }
            if (lastUserIndex < 0) history else history.mapIndexed { index, entry -> if (index > lastUserIndex) entry.copy(superseded = true) else entry }
        }
        return ask(null, emptyList())
    }

    override fun startNewChat() {
        currentConversationId.value = null
        chatHistory.value = emptyList()
    }

    override suspend fun popLastExchange() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER && !it.superseded }
            if (lastUserIndex < 0) history else history.mapIndexed { index, entry -> if (index >= lastUserIndex) entry.copy(superseded = true) else entry }
        }
    }

    override suspend fun truncateFrom(messageId: String) {
        chatHistory.update { history ->
            val cutoff = history.indexOfFirst { it.id == messageId }
            if (cutoff < 0) history else history.mapIndexed { index, entry -> if (index >= cutoff) entry.copy(superseded = true) else entry }
        }
    }

    override fun restoreCurrentConversation() {
        restoreCurrentConversationCalls++
    }

    override fun getToolDefinitions(): List<ToolInfo> = CommonTools.commonToolDefinitions
        .filter { it.userToggleable }

    override fun setToolEnabled(toolId: String, enabled: Boolean) {
    }

    // MCP servers
    private val mcpServers = mutableListOf<McpServerConfig>()
    private val mcpConnected = mutableSetOf<String>()
    private val mcpTools = mutableMapOf<String, List<ToolInfo>>()

    override fun getMcpServers(): List<McpServerConfig> = mcpServers.toList()

    override suspend fun addMcpServer(name: String, url: String, headers: Map<String, String>): McpServerConfig {
        val id = name.lowercase().replace(Regex("[^a-z0-9]"), "_").take(30)
        val config = McpServerConfig(id = id, name = name, url = url, headers = headers)
        mcpServers.add(config)
        return config
    }

    override fun removeMcpServer(serverId: String) {
        mcpServers.removeAll { it.id == serverId }
        mcpConnected.remove(serverId)
        mcpTools.remove(serverId)
    }

    override fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        val index = mcpServers.indexOfFirst { it.id == serverId }
        if (index >= 0) {
            mcpServers[index] = mcpServers[index].copy(isEnabled = enabled)
        }
        if (!enabled) {
            mcpConnected.remove(serverId)
            mcpTools.remove(serverId)
        }
    }

    override suspend fun connectMcpServer(serverId: String): Result<List<ToolInfo>> {
        mcpConnected.add(serverId)
        return Result.success(mcpTools[serverId] ?: emptyList())
    }

    override fun getMcpToolsForServer(serverId: String): List<ToolInfo> = mcpTools[serverId] ?: emptyList()

    override fun isMcpServerConnected(serverId: String): Boolean = serverId in mcpConnected

    var lastActiveSkillId: String? = null
    private val _installedSkills = MutableStateFlow<List<com.maximiliano.maxit.skills.SkillManifest>>(emptyList())
    override val installedSkillsFlow: StateFlow<List<com.maximiliano.maxit.skills.SkillManifest>> = _installedSkills
    var skills: List<com.maximiliano.maxit.skills.SkillManifest>
        get() = _installedSkills.value
        set(value) {
            _installedSkills.value = value
        }

    override fun getInstalledSkills(): List<com.maximiliano.maxit.skills.SkillManifest> = skills
    override suspend fun uninstallSkill(id: String) {}
    override suspend fun importSkill(file: PlatformFile): Result<com.maximiliano.maxit.skills.SkillManifest> = Result.failure(NotImplementedError())
    override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<com.maximiliano.maxit.skills.SkillManifest> = Result.failure(NotImplementedError())

    override suspend fun connectEnabledMcpServers() {
        mcpServers.filter { it.isEnabled }.forEach { mcpConnected.add(it.id) }
    }

    // Soul (system prompt)
    private var userProfile = com.maximiliano.maxit.data.UserProfile()

    override fun getUserProfile() = userProfile

    override fun setUserProfile(profile: com.maximiliano.maxit.data.UserProfile) {
        userProfile = profile
    }

    private var soulText = ""

    override fun getSoulText(): String = soulText

    override fun setSoulText(text: String) {
        soulText = text
    }

    override suspend fun getActiveSystemPrompt(variant: SystemPromptVariant): String? = soulText.ifEmpty { null }

    // Memory management
    private var dynamicUiEnabled = true

    override fun getPendingEmailCount(): Int = 0

    override fun getEmailSyncStates(): Map<String, EmailSyncState> = emptyMap()

    override suspend fun pollEmailAccount(accountId: String) {}

    override fun isDynamicUiEnabled(): Boolean = dynamicUiEnabled

    override fun setDynamicUiEnabled(enabled: Boolean) {
        dynamicUiEnabled = enabled
    }

    private var themeMode: ThemeMode = ThemeMode.System

    override fun getThemeMode(): ThemeMode = themeMode

    override fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
    }

    private var interactiveMode = false

    override fun setInteractiveMode(enabled: Boolean) {
        interactiveMode = enabled
    }

    override fun isInteractiveModeActive(): Boolean = interactiveMode

    private var memoryEnabled = true
    private val memories = mutableListOf<MemoryEntry>()

    override fun isMemoryEnabled(): Boolean = memoryEnabled

    override fun setMemoryEnabled(enabled: Boolean) {
        memoryEnabled = enabled
    }

    override fun getMemories(): List<MemoryEntry> = memories.toList()

    override suspend fun deleteMemory(key: String) {
        memories.removeAll { it.key == key }
    }

    override suspend fun updateMemoryContent(key: String, content: String) {
        val index = memories.indexOfFirst { it.key == key }
        if (index >= 0) {
            memories[index] = memories[index].copy(content = content)
        }
    }

    // Scheduling management
    private var schedulingEnabled = true
    private val scheduledTasks = mutableListOf<ScheduledTask>()

    override fun isSchedulingEnabled(): Boolean = schedulingEnabled

    override fun setSchedulingEnabled(enabled: Boolean) {
        schedulingEnabled = enabled
    }

    override fun getScheduledTasks(): List<ScheduledTask> = scheduledTasks.toList()

    override suspend fun cancelScheduledTask(id: String) {
        scheduledTasks.removeAll { it.id == id }
    }

    // Daemon mode
    private var daemonEnabled = false

    override fun isDaemonEnabled(): Boolean = daemonEnabled

    override fun setDaemonEnabled(enabled: Boolean) {
        daemonEnabled = enabled
    }

    override fun isSandboxEnabled(): Boolean = true

    override fun setSandboxEnabled(enabled: Boolean) {
    }

    var storedSandboxDistro: LinuxDistro = LinuxDistro.DEFAULT

    override fun getSandboxDistro(): LinuxDistro = storedSandboxDistro

    override fun setSandboxDistro(distro: LinuxDistro) {
        storedSandboxDistro = distro
    }

    var storedKaiBuildLaunchAgent: String? = null

    override fun getKaiBuildLaunchAgent(): String? = storedKaiBuildLaunchAgent

    override fun setKaiBuildLaunchAgent(agentId: String?) {
        storedKaiBuildLaunchAgent = agentId
    }

    override fun getHeartbeatConfig(): HeartbeatConfig = HeartbeatConfig()

    override fun setHeartbeatEnabled(enabled: Boolean) {
    }

    override fun setHeartbeatIntervalMinutes(minutes: Int) {
    }

    override fun setHeartbeatActiveHours(start: Int, end: Int) {
    }

    override fun getHeartbeatPrompt(): String = ""

    override fun setHeartbeatPrompt(text: String) {
    }

    override fun getHeartbeatLog(): List<HeartbeatLogEntry> = emptyList()

    override fun getHeartbeatInstanceId(): String? = null
    override fun setHeartbeatInstanceId(instanceId: String?) {}

    override suspend fun askWithTools(prompt: String, instanceId: String?, conversationIdOverride: String?): String = ""
    override suspend fun askSilently(question: String): String = ""
    override suspend fun getOrCreateHeartbeatConversationId(): String = "fake-heartbeat-conversation"

    override val hasUnreadHeartbeat: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override fun clearUnreadHeartbeat() {
        hasUnreadHeartbeat.value = false
    }

    override val openHeartbeatRequested: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override fun requestOpenHeartbeat() {
        openHeartbeatRequested.value = true
    }
    override fun consumeOpenHeartbeatRequest() {
        openHeartbeatRequested.value = false
    }

    override val openAssistRequested: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override fun requestOpenAssist() {
        openAssistRequested.value = true
    }
    override fun consumeOpenAssistRequest() {
        openAssistRequested.value = false
    }

    override val pendingShareText: MutableStateFlow<String?> = MutableStateFlow(null)
    override fun requestOpenShare(text: String) {
        pendingShareText.value = text
    }
    override fun consumeOpenShareRequest() {
        pendingShareText.value = null
    }

    var restoreCurrentConversationCalls = 0

    // Email management
    private var emailEnabled = true
    private val emailAccounts = mutableListOf<EmailAccount>()
    private var emailPollIntervalMinutes = 15

    override fun isEmailEnabled(): Boolean = emailEnabled

    override fun setEmailEnabled(enabled: Boolean) {
        emailEnabled = enabled
    }

    override fun getEmailAccounts(): List<EmailAccount> = emailAccounts.toList()

    override suspend fun removeEmailAccount(id: String) {
        emailAccounts.removeAll { it.id == id }
    }

    override fun getEmailPollIntervalMinutes(): Int = emailPollIntervalMinutes

    override fun setEmailPollIntervalMinutes(minutes: Int) {
        emailPollIntervalMinutes = minutes
    }

    // SMS management
    private var smsEnabled = false
    private var smsPollIntervalMinutes = 15
    private var smsPermissionGranted = false
    private var smsSyncState = SmsSyncState()

    override fun isSmsEnabled(): Boolean = smsEnabled

    override fun setSmsEnabled(enabled: Boolean) {
        smsEnabled = enabled
    }

    override fun getSmsPollIntervalMinutes(): Int = smsPollIntervalMinutes

    override fun setSmsPollIntervalMinutes(minutes: Int) {
        smsPollIntervalMinutes = minutes
    }

    override fun getPendingSmsCount(): Int = 0

    override fun getSmsSyncState(): SmsSyncState = smsSyncState

    override fun hasSmsPermission(): Boolean = smsPermissionGranted

    override suspend fun requestSmsPermission(): Boolean {
        smsPermissionGranted = true
        return true
    }

    override suspend fun pollSms() {}

    private var smsSendEnabled = false
    private var smsSendPermissionGranted = false
    private val _smsDrafts = kotlinx.coroutines.flow.MutableStateFlow(emptyList<SmsDraft>())

    override fun isSmsSendEnabled(): Boolean = smsSendEnabled
    override fun setSmsSendEnabled(enabled: Boolean) {
        smsSendEnabled = enabled
    }
    override fun hasSmsSendPermission(): Boolean = smsSendPermissionGranted
    override suspend fun requestSmsSendPermission(): Boolean {
        smsSendPermissionGranted = true
        return true
    }
    override val smsDrafts: kotlinx.coroutines.flow.StateFlow<List<SmsDraft>> = _smsDrafts
    override suspend fun sendSmsDraft(draftId: String): Boolean = true
    override suspend fun discardSmsDraft(draftId: String) {
        _smsDrafts.value = _smsDrafts.value.filterNot { it.id == draftId }
    }

    private var notificationsEnabled = false
    private var notificationListenerAccessGranted = false

    override fun isNotificationsEnabled(): Boolean = notificationsEnabled
    override fun setNotificationsEnabled(enabled: Boolean) {
        notificationsEnabled = enabled
    }
    override fun isNotificationListenerAccessGranted(): Boolean = notificationListenerAccessGranted
    override fun openNotificationListenerSettings() {}
    override fun getPendingNotificationCount(): Int = 0
    override fun getNotificationSyncState(): com.maximiliano.maxit.data.NotificationSyncState = com.maximiliano.maxit.data.NotificationSyncState()
    override suspend fun clearPendingNotifications() {}

    private var uiScale: Float = 1.0f

    override fun getUiScale(): Float = uiScale

    override fun setUiScale(scale: Float) {
        uiScale = scale
    }

    override fun exportSettingsToJson(sections: Set<ImportSection>): String = "{}"

    override fun getExportPreview(): Map<ImportSection, String?> = emptyMap()

    override fun importSettingsFromJson(json: String, sections: Set<ImportSection>, replace: Boolean): Int = 0

    // On-device inference (LiteRT)
    var localInferenceAvailable = false
    var fakeLocalDownloadedModels: List<DownloadedModel> = emptyList()
    override fun isLocalInferenceAvailable(): Boolean = localInferenceAvailable
    override fun getLocalEngineState(): StateFlow<EngineState>? = null
    override fun getLocalDownloadedModels(): List<DownloadedModel> = fakeLocalDownloadedModels
    override fun getLocalAvailableModels(): List<LocalModel> = emptyList()
    override fun getLocalImportedModels(): List<LocalModel> = emptyList()
    override fun getLocalFreeSpaceBytes(): Long = 0L
    override fun getTotalDeviceMemoryBytes(): Long = Long.MAX_VALUE
    override fun getModelContextTokens(modelId: String): Int = 0
    override fun setModelContextTokens(modelId: String, contextTokens: Int) {}
    override suspend fun releaseLocalEngine() {}
    override fun getLocalDownloadingModelId(): StateFlow<String?>? = null
    override fun getLocalDownloadProgress(): StateFlow<Float?>? = null
    override fun getLocalDownloadError(): StateFlow<DownloadError?>? = null
    override fun getLocalImportingFileName(): StateFlow<String?>? = null
    override fun getLocalImportProgress(): StateFlow<Float?>? = null
    override fun getLocalImportError(): StateFlow<ModelImportError?>? = null
    override fun startLocalModelDownload(model: LocalModel) {}
    override fun cancelLocalModelDownload() {}
    override suspend fun importLocalModel(source: PlatformFile): ModelImportResult = ModelImportResult.Failure(ModelImportError.COPY_FAILED)
    override fun cancelLocalModelImport() {}
    override suspend fun deleteLocalModel(modelId: String) {}
}
