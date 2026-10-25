// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.MemoryStore
import com.maximiliano.maxit.data.TaskStore
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.network.tools.ToolInfo
import com.maximiliano.maxit.tools.CommonTools
import com.maximiliano.maxit.tools.buildAgentToolSet
import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.download
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Js) {
    config(this)
}

actual fun getBackgroundDispatcher(): CoroutineContext = EmptyCoroutineContext

actual fun onDragAndDropEventDropped(event: DragAndDropEvent): PlatformFile? = null

actual val BackIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack

actual val currentPlatform: Platform = Platform.Web

actual val defaultUiScale: Float = 1.0f

actual val isEmailSupported: Boolean = false

actual val isSmsSupported: Boolean = false

actual val isNotificationsSupported: Boolean = false

actual suspend fun compressImageBytes(bytes: ByteArray, mimeType: String): ByteArray = bytes

actual fun getAppFilesDirectory(): String {
    // Web uses localStorage, return empty string as no file path is needed
    return ""
}

actual fun createSecureSettings(): Settings {
    // Web has no secure storage - using localStorage
    return StorageSettings()
}

actual fun createLegacySettings(): Settings? = null // Same storage location, no migration needed

actual fun getPlatformToolDefinitions(): List<ToolInfo> = CommonTools.commonToolDefinitions

private object WebKoinHelper : KoinComponent {
    val appSettings: AppSettings by inject()
    val memoryStore: MemoryStore by inject()
    val taskStore: TaskStore by inject()
    val mcpServerManager: McpServerManager by inject()
}

actual fun getAvailableTools(): List<Tool> = buildAgentToolSet(
    appSettings = WebKoinHelper.appSettings,
    memoryStore = WebKoinHelper.memoryStore,
    taskStore = WebKoinHelper.taskStore,
    mcpServerManager = WebKoinHelper.mcpServerManager,
)

actual fun openUrl(url: String): Boolean = try {
    kotlinx.browser.window.open(url, "_blank")
    true
} catch (_: Exception) {
    false
}

actual fun decodeToImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) {
    null
}

@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back gesture on web
}

actual suspend fun saveFileToDevice(bytes: ByteArray, baseName: String, extension: String) {
    FileKit.download(bytes = bytes, fileName = "$baseName.$extension")
}

// Web notifications API isn't wired up; stub.
actual fun shouldSendLlmNotification(): Boolean = true
actual fun sendHeartbeatNotification(title: String, body: String) = Unit
actual fun sendLlmNotification(title: String, body: String, conversationId: String?, requestId: String?) = Unit
