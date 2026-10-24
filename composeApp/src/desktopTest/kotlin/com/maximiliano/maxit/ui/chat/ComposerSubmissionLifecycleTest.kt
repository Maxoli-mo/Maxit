@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.maximiliano.maxit.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.maximiliano.maxit.ui.chat.composables.QuestionInput
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerSubmissionLifecycleTest {
    private class Fixture(restoredValues: Map<String, List<Any?>>? = null, ownerId: String? = null) : AutoCloseable {
        val questions = mutableListOf<String>()
        private val actions = ChatActions(
            ask = { questions += it }, toggleSpeechOutput = {}, retry = {}, clearHistory = {},
            setIsSpeaking = { _, _ -> }, addFile = {}, removeFile = {}, startNewChat = {},
            regenerate = {}, cancel = {}, selectService = {}, loadConversation = {}, deleteConversation = {},
            clearUnreadHeartbeat = {}, clearSnackbar = {}, undoDeleteConversation = {},
            submitUiCallback = { _, _ -> }, resubmit = { _, _, _ -> }, enterInteractiveMode = {},
            exitInteractiveMode = {}, goBackInteractiveMode = {}, sendSmsDraft = {}, discardSmsDraft = {},
            consumeComposerPrefill = {},
        )
        var state by mutableStateOf(ChatUiState(actions = actions).let { if (ownerId != null) it.copy(inputOwnerId = ownerId) else it })
        var draft by mutableStateOf(TextFieldValue("  submitted draft  "))
        var visible by mutableStateOf(true)
        lateinit var submit: (String) -> Unit
        private var time = 0L
        private var failure: Throwable? = null
        private val registry = SaveableStateRegistry(restoredValues = restoredValues) { true }
        private val scene = ImageComposeScene(
            380,
            300,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failure = error },
        ) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                // Both normal/sandbox and interactive/collapsed call sites keep this beside the draft.
                val submitQuestion = rememberComposerSubmit(state, { draft }) { draft = it }
                if (visible) {
                    submit = submitQuestion
                    MaterialTheme {
                        QuestionInput(
                            files = persistentListOf(),
                            addFile = {},
                            removeFile = {},
                            ask = submitQuestion,
                            supportedFileExtensions = persistentListOf(),
                            textState = draft,
                            onTextStateChange = { draft = it },
                            isLoading = state.isLoading,
                        )
                    }
                }
            }
        }

        fun frames() {
            repeat(8) {
                scene.render(time).close()
                failure?.let { throw AssertionError("Composer effect failed", it) }
                time += 50_000_000L
            }
        }

        fun sendAndHide() {
            frames()
            submit(draft.text)
            state = state.copy(isLoading = true)
            visible = false
            frames()
            assertEquals(listOf("submitted draft"), questions)
        }

        fun saveState() = registry.performSave()

        override fun close() = scene.close()
    }

    @Test
    fun `hidden composer processes delayed acceptance and duplicate finalization only once`() = SwingUtilities.invokeAndWait {
        Fixture().use { fixture ->
            fixture.sendAndHide()
            fixture.state = fixture.state.copy(acceptedInputId = 1)
            fixture.frames()
            assertEquals("", fixture.draft.text)
            fixture.draft = TextFieldValue("later edit")
            fixture.state = fixture.state.copy(isLoading = false, settledInputId = 1)
            fixture.frames()
            fixture.visible = true
            fixture.frames()
            assertEquals("later edit", fixture.draft.text)
            assertEquals(1, fixture.questions.size)
        }
    }

    @Test
    fun `later edits survive acceptance while composer is hidden`() = SwingUtilities.invokeAndWait {
        Fixture().use { fixture ->
            fixture.sendAndHide()
            fixture.draft = TextFieldValue("changed while pending")
            fixture.state = fixture.state.copy(acceptedInputId = 1, settledInputId = 1, isLoading = false)
            fixture.frames()
            fixture.visible = true
            fixture.frames()
            assertEquals("changed while pending", fixture.draft.text)
        }
    }

    @Test
    fun `restored pending submission can recognize its same owners acceptance`() = SwingUtilities.invokeAndWait {
        lateinit var owner: String
        val saved = Fixture().use { fixture ->
            fixture.sendAndHide()
            owner = fixture.state.inputOwnerId
            fixture.saveState()
        }
        Fixture(saved, owner).use { fixture ->
            fixture.state = fixture.state.copy(acceptedInputId = 1, settledInputId = 1)
            fixture.frames()
            assertEquals("", fixture.draft.text)
        }
    }

    @Test
    fun `restoring a new owner never clears unacknowledged text when counters reset`() = SwingUtilities.invokeAndWait {
        val saved = Fixture().use { fixture ->
            fixture.state = fixture.state.copy(acceptedInputId = 7, settledInputId = 7)
            fixture.sendAndHide()
            fixture.saveState()
        }
        Fixture(saved).use { fixture ->
            fixture.frames()
            assertEquals("  submitted draft  ", fixture.draft.text)
            fixture.submit(fixture.draft.text)
            assertEquals(listOf("submitted draft"), fixture.questions)
        }
    }

    @Test
    fun `rejected hidden draft is preserved and can be submitted again`() = SwingUtilities.invokeAndWait {
        Fixture().use { fixture ->
            fixture.sendAndHide()
            fixture.state = fixture.state.copy(settledInputId = 1, isLoading = false)
            fixture.frames()
            fixture.visible = true
            fixture.frames()
            assertEquals("  submitted draft  ", fixture.draft.text)
            fixture.submit(fixture.draft.text)
            fixture.state = fixture.state.copy(isLoading = true)
            fixture.frames()
            fixture.state = fixture.state.copy(acceptedInputId = 2, settledInputId = 2, isLoading = false)
            fixture.frames()
            assertEquals("", fixture.draft.text)
            assertEquals(2, fixture.questions.size)
        }
    }
}
