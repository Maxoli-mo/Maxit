// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.ConversationStorage
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.EmailStore
import com.maximiliano.maxit.data.HeartbeatManager
import com.maximiliano.maxit.data.MemoryStore
import com.maximiliano.maxit.data.NotificationStore
import com.maximiliano.maxit.data.RemoteDataRepository
import com.maximiliano.maxit.data.SmsDraftStore
import com.maximiliano.maxit.data.SmsStore
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.data.TaskStore
import com.maximiliano.maxit.data.ToolExecutor
import com.maximiliano.maxit.data.createConversationPersistence
import com.maximiliano.maxit.data.runMigrations
import com.maximiliano.maxit.email.EmailPoller
import com.maximiliano.maxit.inference.createLocalInferenceEngine
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.Requests
import com.maximiliano.maxit.notifications.NotificationReader
import com.maximiliano.maxit.skills.SkillManager
import com.maximiliano.maxit.sms.SmsPoller
import com.maximiliano.maxit.sms.SmsReader
import com.maximiliano.maxit.sms.SmsSender
import com.maximiliano.maxit.splinterlands.SplinterlandsApi
import com.maximiliano.maxit.splinterlands.SplinterlandsBattleRunner
import com.maximiliano.maxit.splinterlands.SplinterlandsStore
import com.maximiliano.maxit.tools.AppPermission
import com.maximiliano.maxit.tools.NotificationListenerController
import com.maximiliano.maxit.tools.PermissionController
import com.maximiliano.maxit.ui.build.KaiBuildViewModel
import com.maximiliano.maxit.ui.chat.ChatViewModel
import com.maximiliano.maxit.ui.sandbox.SandboxFileBrowserViewModel
import com.maximiliano.maxit.ui.sandbox.SandboxPackagesViewModel
import com.maximiliano.maxit.ui.sandbox.SandboxSessionViewModel
import com.maximiliano.maxit.ui.settings.SandboxViewModel
import com.maximiliano.maxit.ui.settings.SettingsViewModel
import com.maximiliano.maxit.ui.settings.SplinterlandsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Picks the file browser bound to Kai Build's Debian rather than the chat sandbox. */
val KAI_BUILD_FILES = named("kaiBuildFiles")

/** Qualifier for the [PermissionController] singleton handling [permission]. */
fun permissionQualifier(permission: AppPermission) = named(permission.name)

val appModule = module {
    AppPermission.entries.forEach { permission ->
        single(permissionQualifier(permission)) { PermissionController(permission) }
    }
    single<SmsReader> { SmsReader() }
    single<SmsSender> { SmsSender() }
    single<NotificationListenerController> { NotificationListenerController() }
    single<NotificationReader> { NotificationReader() }
    single<AppSettings> {
        AppSettings(createSecureSettings()).also {
            it.runMigrations(createLegacySettings())
        }
    }
    single<Requests> {
        Requests()
    }
    single<ConversationStorage> {
        ConversationStorage(get(), createConversationPersistence(get()))
    }
    single<ToolExecutor> {
        ToolExecutor()
    }
    single<MemoryStore> {
        MemoryStore(get())
    }
    single<TaskStore> {
        TaskStore(get())
    }
    single<EmailStore> {
        EmailStore(get())
    }
    single<EmailPoller> {
        EmailPoller(get<EmailStore>())
    }
    single<SmsStore> {
        SmsStore(get())
    }
    single<SmsPoller> {
        SmsPoller(get<SmsStore>(), get<SmsReader>())
    }
    single<SmsDraftStore> {
        SmsDraftStore(get())
    }
    single<NotificationStore> {
        NotificationStore(get())
    }
    single<SplinterlandsStore> {
        SplinterlandsStore(get())
    }
    single<SplinterlandsApi> {
        SplinterlandsApi()
    }
    single<HeartbeatManager> {
        HeartbeatManager(get(), get(), get(), get())
    }
    single<McpServerManager> {
        McpServerManager(get())
    }
    single<SkillManager> {
        SkillManager(get<SandboxController>())
    }
    single<RemoteDataRepository> {
        RemoteDataRepository(
            requests = get(),
            appSettings = get(),
            conversationStorage = get(),
            toolExecutor = get(),
            memoryStore = get(),
            taskStore = get(),
            heartbeatManager = get(),
            emailStore = get(),
            emailPoller = get(),
            smsStore = get(),
            smsPoller = get(),
            smsReader = get(),
            smsPermissionController = get(permissionQualifier(AppPermission.READ_SMS)),
            smsSendPermissionController = get(permissionQualifier(AppPermission.SEND_SMS)),
            smsSender = get(),
            smsDraftStore = get(),
            notificationStore = get(),
            notificationListenerController = get(),
            mcpServerManager = get(),
            skillManager = get(),
            sandboxController = get(),
            localInferenceEngine = createLocalInferenceEngine(),
        )
    }
    single<DataRepository> { get<RemoteDataRepository>() }
    single<SplinterlandsBattleRunner> {
        SplinterlandsBattleRunner(get(), get(), get<DataRepository>(), get<DaemonController>())
    }
    single<TaskScheduler> {
        TaskScheduler(
            get<DataRepository>(),
            get(),
            get(),
            get(),
            get(),
            get<EmailPoller>(),
            get<SmsStore>(),
            get<SmsPoller>(),
            get<NotificationStore>(),
        )
    }
    single<DaemonController> { createDaemonController() }
    single<SandboxController> { createSandboxController() }
    single<KaiBuildController> { createKaiBuildController() }
    viewModel { SettingsViewModel(get<DataRepository>(), get<DaemonController>(), get(permissionQualifier(AppPermission.POST_NOTIFICATIONS)), get<TaskScheduler>(), localNetworkPermissionController = get(permissionQualifier(AppPermission.LOCAL_NETWORK))) }
    viewModel { SandboxViewModel(get<DataRepository>(), get<SandboxController>()) }
    viewModel { SandboxFileBrowserViewModel(get<SandboxController>()) }
    viewModel { SandboxPackagesViewModel(get<SandboxController>()) }
    viewModel { SandboxSessionViewModel(get<SandboxController>(), get<DataRepository>()) }
    viewModel { KaiBuildViewModel(get<KaiBuildController>(), get<DataRepository>()) }
    // Same browser, second environment: Kai Build's Debian instead of the chat sandbox.
    viewModel(KAI_BUILD_FILES) { SandboxFileBrowserViewModel(get<KaiBuildController>().files) }
    viewModel { SplinterlandsViewModel(get<DataRepository>(), get(), get(), get<SplinterlandsApi>()) }
    viewModel { ChatViewModel(get<DataRepository>(), get<TaskScheduler>(), localNetworkPermissionController = get(permissionQualifier(AppPermission.LOCAL_NETWORK))) }
}
