@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.maximiliano.maxit.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.data.Service
import com.maximiliano.maxit.ui.DarkColorScheme
import com.maximiliano.maxit.ui.LightColorScheme
import com.maximiliano.maxit.ui.withBlackBackground
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Render synthetic state without opening a window, connecting an account or accessing a phone. */
class CodexSettingsLayoutTest {
    @Test
    fun `sign in stays compact and readable at phone desktop and large text sizes`() {
        for ((width, fontScale) in listOf(380 to 1f, 320 to 1.3f, 320 to 2f, 820 to 1f)) {
            for (dark in listOf(false, true)) {
                SwingUtilities.invokeAndWait { render(width, fontScale, dark) }
            }
        }
    }

    private fun render(width: Int, fontScale: Float, dark: Boolean) {
        var contentHeight = 0
        var cancelled = false
        var openedUri: String? = null
        var browserUnavailable = false
        val colors = if (dark) DarkColorScheme.withBlackBackground() else LightColorScheme
        val scene = ImageComposeScene(width, 1400, density = Density(1f, fontScale)) {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalContentColor provides Color.Black,
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        check(!browserUnavailable) { "Fixture: no browser" }
                        openedUri = uri
                    }
                },
            ) {
                MaterialTheme(colorScheme = colors) {
                    Box(Modifier.fillMaxSize().background(colors.background).padding(16.dp)) {
                        Column(Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height }) {
                            ServicesContent(
                                SettingsUiState(
                                    configuredServices = persistentListOf(
                                        ConfiguredServiceEntry(
                                            instanceId = "codex-layout",
                                            service = Service.OpenAICodex,
                                            codexAuth = CodexAuthState(
                                                status = CodexAuthStatus.Waiting,
                                                userCode = "TEST-1234",
                                                verificationUri = "https://auth.openai.com/codex/device",
                                            ),
                                        ),
                                    ),
                                    expandedServiceId = "codex-layout",
                                ),
                                SettingsActions.NoOp.copy(onCancelCodexSignIn = { cancelled = true }),
                            )
                        }
                    }
                }
            }
        }
        try {
            repeat(8) { scene.render(it * 50_000_000L).close() }
            fun nodes(): List<SemanticsNode> {
                fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
                return scene.semanticsOwners.flatMap { visit(it.unmergedRootSemanticsNode) }
            }
            fun text(value: String) = nodes().last { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
            }
            fun action(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val code = text("TEST-1234")
            val copy = action(
                nodes().last {
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Copy code")
                },
            )
            val open = action(text("Open sign-in"))
            val cancel = action(text("Cancel"))
            val prefix = "waiting-$width-$fontScale-${if (dark) "dark" else "light"}"
            val output = File("build/reports/codex-design").apply { mkdirs() }
            scene.render(400_000_000L).use { image ->
                checkNotNull(image.encodeToData()).use { File(output, "$prefix.png").writeBytes(it.bytes) }
            }
            if (fontScale == 1f) {
                assertTrue(contentHeight <= 380, "$prefix: sign-in card grew to $contentHeight px")
                assertTrue(abs(code.boundsInRoot.center.y - copy.boundsInRoot.center.y) < 24f, "Copy belongs beside the code: ${code.boundsInRoot} vs ${copy.boundsInRoot}")
                assertTrue(abs(open.boundsInRoot.center.y - cancel.boundsInRoot.center.y) < 4f, "Actions should share a row at normal text size")
            }
            for (button in listOf(copy, open, cancel)) {
                assertTrue(button.boundsInRoot.width >= 48f && button.boundsInRoot.height >= 48f, "$prefix: touch target is too small")
                assertTrue(button.boundsInRoot.left >= 16 && button.boundsInRoot.right <= width - 16, "$prefix: action overflows card")
            }
            val layouts = mutableListOf<TextLayoutResult>()
            for (value in listOf("TEST-1234", "Enter this code on the sign-in page.", "Open sign-in", "Cancel", "Waiting for approval…")) {
                text(value).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            }
            assertEquals(5, layouts.size)
            val cardBackground = if (dark) colors.background else colors.surfaceVariant.copy(alpha = 0.5f).compositeOver(colors.background)
            val backgrounds = listOf(colors.surfaceContainerHighest, cardBackground, colors.primary, cardBackground, cardBackground)
            layouts.zip(backgrounds).forEach { (layout, background) ->
                // Text layout uses fractional pixels; raster bounds round to whole pixels.
                assertTrue(
                    !layout.multiParagraph.didExceedMaxLines &&
                        (0 until layout.lineCount).all { layout.getLineRight(it) <= layout.size.width + 1f } &&
                        layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f,
                    "$prefix: '${layout.layoutInput.text}' is clipped",
                )
                val foreground = layout.layoutInput.style.color.compositeOver(background)
                val contrast = (max(foreground.luminance(), background.luminance()) + 0.05f) /
                    (min(foreground.luminance(), background.luminance()) + 0.05f)
                assertTrue(contrast >= 4.5f, "$prefix: text contrast is only $contrast")
            }
            assertFalse(nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "https://auth.openai.com/codex/device" } })
            assertEquals(null, openedUri, "Rendering must not open the browser")
            assertTrue(open.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("https://auth.openai.com/codex/device", openedUri)
            browserUnavailable = true
            assertTrue(open.config[SemanticsActions.OnClick].action!!.invoke())
            repeat(4) { scene.render(450_000_000L + it * 50_000_000L).close() }
            text("https://auth.openai.com/codex/device")
            text("Could not open your browser. Copy the address below into a browser on this or another device.")
            assertTrue(cancel.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(cancelled)
        } finally {
            scene.close()
        }
    }
}
