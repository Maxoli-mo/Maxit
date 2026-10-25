// Modified for Maxit, 2026-10-20.

@file:Suppress("ktlint:standard:filename")
@file:OptIn(ExperimentalDesktopTarget::class)

package com.maximiliano.maxit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.logo
import nl.marc_apps.tts.TextToSpeechEngine
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.experimental.ExperimentalDesktopTarget
import nl.marc_apps.tts.rememberTextToSpeechOrNull
import org.jetbrains.compose.resources.painterResource
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    application {
        val display = remember { desktopDisplay() }
        val windowState = rememberWindowState(size = display.windowSize)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Maxit",
            icon = painterResource(Res.drawable.logo),
        ) {
            DisposableEffect(window) {
                desktopWindowFocused = window.isFocused
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent) {
                        desktopWindowFocused = true
                    }
                    override fun windowLostFocus(event: WindowEvent) {
                        desktopWindowFocused = false
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            // Defer TTS initialization until after the first frame
            var ttsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { ttsReady = true }
            val textToSpeech: TextToSpeechInstance? = if (ttsReady) {
                rememberTextToSpeechOrNull(TextToSpeechEngine.SystemDefault)
            } else {
                null
            }

            val nativeDensity = LocalDensity.current
            val density = Density(display.scaleFor(nativeDensity.density), nativeDensity.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                val navController = rememberNavController()
                App(
                    navController = navController,
                    textToSpeech = textToSpeech,
                )
            }
        }
    }
}
