@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.maximiliano.maxit.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.data.UserProfile
import com.maximiliano.maxit.ui.DarkColorScheme
import com.maximiliano.maxit.ui.LightColorScheme
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSettingsLayoutTest {
    @Test
    fun `profile and instructions fit narrow wide and large text layouts and save separately`() {
        for ((width, fontScale) in listOf(380 to 1f, 320 to 2f, 820 to 1f)) {
            for (dark in listOf(false, true)) SwingUtilities.invokeAndWait { render(width, fontScale, dark) }
        }
    }

    private fun render(width: Int, fontScale: Float, dark: Boolean) {
        val state = mutableStateOf(SettingsUiState(userProfile = UserProfile("Taylor Example", "Sam")))
        var profileSaves = 0
        var instructionSaves = 0
        val colors = if (dark) DarkColorScheme else LightColorScheme
        val scene = ImageComposeScene(width, 3000, density = Density(1f, fontScale)) {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalContentColor provides Color.Black) {
                MaterialTheme(colorScheme = colors) {
                    Box(Modifier.fillMaxSize().background(colors.background).padding(16.dp)) {
                        ProfileContent(
                            state.value,
                            SettingsActions.NoOp.copy(
                                onSaveProfile = {
                                    state.value = state.value.copy(userProfile = it)
                                    profileSaves++
                                },
                                onSaveSoul = {
                                    state.value = state.value.copy(soulText = it)
                                    instructionSaves++
                                },
                            ),
                        )
                    }
                }
            }
        }
        var frame = 0L
        fun renderFrames() = repeat(5) { scene.render(frame++ * 50_000_000L).close() }
        fun nodes(): List<SemanticsNode> {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            return scene.semanticsOwners.flatMap { visit(it.unmergedRootSemanticsNode) }
        }
        fun tagged(tag: String) = nodes().last { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun field(tag: String) = nodes().last { node ->
            node.config.getOrNull(SemanticsActions.SetText) != null &&
                generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        }
        fun edit(tag: String, text: String) {
            assertTrue(field(tag).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
            renderFrames()
        }
        try {
            renderFrames()
            for (heading in listOf("Profile", "Custom instructions")) {
                val node = nodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == heading } &&
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null
                }
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertEquals(colors.onSurface, layouts.single().layoutInput.style.color)
                assertFalse(layouts.single().multiParagraph.didExceedMaxLines)
            }
            assertEquals("", field("custom-instructions").config[SemanticsProperties.EditableText].text)
            assertTrue(tagged("save-profile").config.contains(SemanticsProperties.Disabled))
            assertTrue(tagged("save-custom-instructions").config.contains(SemanticsProperties.Disabled))
            for (tag in listOf("profile-full-name", "profile-preferred-name", "custom-instructions", "save-profile", "save-custom-instructions")) {
                val bounds = tagged(tag).boundsInRoot
                assertTrue(bounds.left >= 16 && bounds.right <= width - 16, "$tag overflows at $width / $fontScale")
                assertTrue(bounds.width >= 48 && bounds.height >= 48, "$tag has a small touch target")
            }
            edit("profile-preferred-name", "River")
            edit("custom-instructions", "Keep answers brief.")
            assertTrue(tagged("save-profile").config[SemanticsActions.OnClick].action!!.invoke())
            renderFrames()
            assertEquals(1, profileSaves)
            assertEquals(0, instructionSaves)
            assertEquals("", state.value.soulText)
            assertEquals("Keep answers brief.", field("custom-instructions").config[SemanticsProperties.EditableText].text)
            edit("profile-full-name", "Unsaved Example")
            assertTrue(tagged("save-custom-instructions").config[SemanticsActions.OnClick].action!!.invoke())
            renderFrames()
            assertEquals(1, instructionSaves)
            assertEquals("Taylor Example", state.value.userProfile.fullName)
            assertEquals("Unsaved Example", field("profile-full-name").config[SemanticsProperties.EditableText].text)
            assertFalse(tagged("save-profile").config.contains(SemanticsProperties.Disabled))
            val output = File("build/reports/profile-design").apply { mkdirs() }
            scene.render(frame++ * 50_000_000L).use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File(output, "profile-$width-$fontScale-${if (dark) "dark" else "light"}.png").writeBytes(data.bytes)
                }
            }
        } finally {
            scene.close()
        }
    }
}
