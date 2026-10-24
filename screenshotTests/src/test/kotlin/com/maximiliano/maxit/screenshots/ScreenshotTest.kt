// Modified for Maxit, 2026-10-20.

@file:OptIn(ExperimentalVoiceApi::class)

package com.maximiliano.maxit.screenshots

import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.maximiliano.maxit.data.MessageState
import com.maximiliano.maxit.data.ToolApprovalRequest
import com.maximiliano.maxit.ui.DarkColorScheme
import com.maximiliano.maxit.ui.LightColorScheme
import com.maximiliano.maxit.ui.Theme
import com.maximiliano.maxit.ui.chat.ChatScreenContent
import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import com.maximiliano.maxit.ui.dynamicui.LocalPreviewImages
import com.maximiliano.maxit.ui.settings.SettingsScreenContent
import kotlinx.collections.immutable.persistentListOf
import nl.marc_apps.tts.experimental.ExperimentalVoiceApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalResourceApi::class)
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9A.copy(softButtons = false),
        showSystemUi = true,
        maxPercentDifference = 0.1,
    )

    private val previewImages = mutableMapOf<String, ImageBitmap>()

    @Before
    fun setup() {
        setResourceReaderAndroidContext(paparazzi.context)
        loadPreviewImage("resource://orc_survival.png", "/orc_survival.png")
        loadPreviewImage("resource://cacio_e_pepe.png", "/cacio_e_pepe.png")
    }

    private fun loadPreviewImage(key: String, resourcePath: String) {
        val bitmap = BitmapFactory.decodeStream(javaClass.getResourceAsStream(resourcePath)) ?: return
        previewImages[key] = bitmap.asImageBitmap()
    }

    fun Paparazzi.snap(
        colorScheme: ColorScheme,
        content: @Composable () -> Unit,
    ) {
        val theme = if (colorScheme == DarkColorScheme) {
            "android:Theme.Material.NoActionBar"
        } else {
            "android:Theme.Material.Light.NoActionBar"
        }
        unsafeUpdateConfig(theme = theme)

        snapshot {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalPreviewImages provides previewImages,
            ) {
                Theme(colorScheme = colorScheme) {
                    content()
                }
            }
        }
    }

    @Test
    fun chatEmptyState_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatEmptyState,
                FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatReliability_interrupted_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithMessages.copy(
                    history = persistentListOf(
                        History(id = "user", role = History.Role.USER, content = "Check the release and send the report once."),
                        History(id = "run", runId = "attempt", role = History.Role.ASSISTANT, content = "Saved work is preserved. Verify the unknown delivery outcome before repeating it.", isStatusMessage = true, state = MessageState.INTERRUPTED),
                        History(id = "partial", runId = "attempt", role = History.Role.ASSISTANT, content = "The release checks passed. Delivery was interrupted before confirmation.", state = MessageState.INTERRUPTED, toolCalls = persistentListOf(ToolCallInfo("call", "compose_email", "{\"to\":\"report@example.invalid\"}", dispatched = true))),
                        History(id = "receipt", runId = "attempt", role = History.Role.TOOL, content = "{\"status\":\"outcome_unknown\"}", toolCallId = "call", toolName = "compose_email"),
                    ),
                ),
                textToSpeech = FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatReliability_approval_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithMessages.copy(
                    isLoading = true,
                    history = persistentListOf(History(id = "user", role = History.Role.USER, content = "Send the reviewed release report.")),
                    toolApprovals = persistentListOf(ToolApprovalRequest("call", "preview", "compose_email", "{\n  \"to\": \"report@example.invalid\",\n  \"subject\": \"Release checks\",\n  \"body\": \"All checks passed.\"\n}", uncertain = true)),
                ),
                textToSpeech = FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatReliability_streaming_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithMessages.copy(
                    isLoading = true,
                    history = persistentListOf(
                        History(id = "user", role = History.Role.USER, content = "Show the work as the checks run."),
                        History(id = "partial", role = History.Role.ASSISTANT, state = MessageState.GENERATING, content = "## Release checks\n\nThe storage migration passed. The remaining checks are running.\n\n```kotlin\nval result ="),
                    ),
                ),
                textToSpeech = FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatWithMessages_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithMessages,
                FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatWithDynamicUi_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithDynamicUi,
                FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun settingsAgent_dark() {
        paparazzi.snap(DarkColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.settingsAgent,
                sandboxState = ScreenshotTestData.sandboxState,
            )
        }
    }

    @Test
    fun settingsFree_dark() {
        paparazzi.snap(DarkColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.freeConnected,
                sandboxState = ScreenshotTestData.sandboxState,
            )
        }
    }

    @Test
    fun settingsTools_light() {
        paparazzi.snap(LightColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.settingsTools,
                sandboxState = ScreenshotTestData.sandboxState,
            )
        }
    }

    @Test
    fun settingsSandbox_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatEmptyState,
                isSandboxAvailable = true,
                initialSandboxOpen = true,
                previewSandboxState = ScreenshotTestData.sandboxState,
                previewSandboxLines = ScreenshotTestData.sandboxTerminalLines,
            )
        }
    }
}
