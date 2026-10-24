@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.maximiliano.maxit.ui.chat

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.ui.components.VerticalScrollbarForList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatScrollStateTest {
    private class Fixture(loading: Boolean = true, savedState: Map<String, List<Any?>>? = null) : AutoCloseable {
        lateinit var list: LazyListState
        val registry = SaveableStateRegistry(savedState) { true }
        var lines by mutableIntStateOf(80)
        var showLoading by mutableStateOf(loading)
        var showError by mutableStateOf(false)
        var conversationId by mutableStateOf("first-chat")
        lateinit var following: ChatScrollState
        private lateinit var scope: CoroutineScope
        private var time = 0L
        private var backgroundFailure: Throwable? = null
        val scene = ImageComposeScene(380, 600, density = Density(1f), coroutineContext = Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> backgroundFailure = error }) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                list = rememberLazyListState()
                scope = rememberCoroutineScope()
                following = rememberChatScrollState(list, conversationId)
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        LazyColumn(Modifier.fillMaxSize().nestedScroll(following), state = list) {
                            items(20) { index -> Text("Earlier message $index", Modifier.fillMaxWidth().height(32.dp)) }
                            item("reply") { Text((1..lines).joinToString("\n") { "Streaming line $it: saved output." } + "\nLATEST REPLY TAIL", Modifier.fillMaxWidth()) }
                            if (showLoading) item("loading") { Text("Generating", Modifier.fillMaxWidth().height(48.dp)) }
                            if (showError) item("error") { Text("Request interrupted. Saved work is kept.", Modifier.fillMaxWidth().height(80.dp)) }
                        }
                        VerticalScrollbarForList(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }

        fun frames(count: Int = 12) {
            repeat(count) {
                scene.render(time).close()
                backgroundFailure?.let { throw AssertionError("Scroll effect failed", it) }
                time += 50_000_000L
            }
        }

        val position get() = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset

        fun drag(delta: Float) = scope.launch {
            list.scroll(MutatePriority.UserInput) {
                scrollBy(delta)
                awaitCancellation()
            }
        }

        fun capture(name: String) {
            val output = File("build/reports/chat-scroll").apply { mkdirs() }
            scene.render(time).use { image ->
                checkNotNull(image.encodeToData()).use { File(output, "$name.png").writeBytes(it.bytes) }
            }
            time += 50_000_000L
        }

        override fun close() = scene.close()
    }

    @Test
    fun `follows a tall last reply and layout-only footer changes`() = SwingUtilities.invokeAndWait {
        Fixture(loading = false).use { f ->
            f.frames()
            assertFalse(f.list.canScrollForward, "Open the chat at the actual end, not the start of its tall last item")
            f.lines += 100
            f.frames()
            assertFalse(f.list.canScrollForward, "A batch taller than the viewport must not release following")
            f.showLoading = true
            f.frames()
            assertFalse(f.list.canScrollForward, "Include the loading row, not just history indices")
            f.showLoading = false
            f.showError = true
            f.frames()
            assertFalse(f.list.canScrollForward, "Follow error/status layout changes without another text delta")
            f.scene.constraints = Constraints.fixed(380, 420)
            f.frames()
            assertFalse(f.list.canScrollForward, "Keep following when the viewport shrinks")
            f.capture("following-tall-reply")
        }
    }

    @Test
    fun `scrolling up pauses through generation and returning to the bottom resumes`() = SwingUtilities.invokeAndWait {
        Fixture().use { f ->
            f.frames()
            f.lines += 20
            f.frames(1)
            val up = f.drag(-240f)
            f.frames()
            assertTrue(f.list.canScrollForward)
            val reading = f.position
            f.lines += 50
            f.frames()
            assertEquals(reading, f.position, "Do not fight an active gesture")
            up.cancel()
            f.frames()
            f.lines += 50
            f.frames()
            assertEquals(reading, f.position, "Keep the reading position after the gesture ends")
            f.capture("paused-for-reading")
            val down = f.drag(100_000f)
            f.frames()
            assertFalse(f.list.canScrollForward)
            down.cancel()
            f.frames()
            f.lines += 50
            f.frames()
            assertFalse(f.list.canScrollForward, "Reaching the bottom must restore following")
            f.capture("resumed-following")
        }
    }

    @Test
    fun `mouse wheel pauses following and the down action restores it`() = SwingUtilities.invokeAndWait {
        Fixture().use { f ->
            f.frames()
            f.scene.sendPointerEvent(PointerEventType.Scroll, Offset(180f, 250f), scrollDelta = Offset(0f, -6f))
            f.frames()
            assertFalse(f.following.isFollowingBottom, "A real upward wheel event must release following")
            f.lines += 60
            f.frames()
            assertTrue(f.list.canScrollForward)
            f.following.scrollToBottom()
            f.frames()
            assertFalse(f.list.canScrollForward)
            f.lines += 60
            f.frames()
            assertFalse(f.list.canScrollForward)
        }
    }

    @Test
    fun `dragging the real scrollbar pauses following`() = SwingUtilities.invokeAndWait {
        Fixture().use { f ->
            f.frames()
            f.scene.sendPointerEvent(PointerEventType.Press, Offset(376f, 580f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            f.scene.sendPointerEvent(PointerEventType.Move, Offset(376f, 320f), buttons = PointerButtons(isPrimaryPressed = true))
            f.frames()
            f.scene.sendPointerEvent(PointerEventType.Release, Offset(376f, 320f), buttons = PointerButtons(), button = PointerButton.Primary)
            f.frames()
            assertTrue(f.list.canScrollForward, "Dragging the scrollbar must move away from the tail")
            assertFalse(f.following.isFollowingBottom)
            val reading = f.position
            f.lines += 60
            f.frames()
            assertEquals(reading, f.position, "Do not pull a scrollbar reader down as text arrives")
        }
    }

    @Test
    fun `restoring the screen preserves its reading position and paused following`() = SwingUtilities.invokeAndWait {
        lateinit var saved: Map<String, List<Any?>>
        var reading: Pair<Int, Int>? = null
        Fixture().use { f ->
            f.frames()
            val up = f.drag(-240f)
            f.frames()
            up.cancel()
            f.frames()
            assertTrue(f.list.canScrollForward)
            saved = f.registry.performSave()
            reading = f.position
        }
        Fixture(savedState = saved).use { f ->
            f.frames()
            assertEquals(reading, f.position, "Restoration must not jump back to the tail")
            f.lines += 60
            f.frames()
            assertEquals(reading, f.position, "Restoration must preserve paused following too")
            f.following.scrollToBottom()
            f.frames()
            assertFalse(f.list.canScrollForward)
        }
    }

    @Test
    fun `interrupted jumps and changing conversations do not leave following disabled`() = SwingUtilities.invokeAndWait {
        Fixture().use { f ->
            f.frames()
            val up = f.drag(-240f)
            f.frames()
            f.following.scrollToBottom()
            f.frames()
            assertTrue(f.list.canScrollForward, "An active user gesture takes priority over a programmatic jump")
            up.cancel()
            f.frames()
            f.conversationId = "second-chat"
            f.frames()
            assertFalse(f.list.canScrollForward, "The new conversation starts following independently")
            f.lines += 60
            f.frames()
            assertFalse(f.list.canScrollForward)
        }
    }
}
