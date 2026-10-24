package com.maximiliano.maxit.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun rememberChatScrollState(listState: LazyListState, conversationId: String?): ChatScrollState = key(conversationId) {
    val scope = rememberCoroutineScope()
    val following = rememberSaveable { mutableStateOf(true) }
    val state = remember(listState, following) { ChatScrollState(listState, scope, following) }
    LaunchedEffect(state) { state.follow() }
    state
}

internal class ChatScrollState(private val listState: LazyListState, private val scope: CoroutineScope, following: MutableState<Boolean>) : NestedScrollConnection {
    var isFollowingBottom by following
        private set
    private var autoScrolling by mutableStateOf(false)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) isFollowingBottom = false
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && (consumed.y < 0f || available.y < 0f) && !listState.canScrollForward) isFollowingBottom = true
        return Offset.Zero
    }

    fun scrollToBottom() {
        scope.launch {
            isFollowingBottom = true
            scrollToBottomNow()
        }
    }

    suspend fun follow() = coroutineScope {
        launch {
            // Scrollbar/accessibility scrolling can bypass nested pointer scrolling.
            snapshotFlow { (listState.isScrollInProgress && !autoScrolling) to !listState.canScrollForward }
                .collect { (userScrolling, atBottom) ->
                    if (userScrolling) isFollowingBottom = atBottom
                }
        }
        // Layout changes include tall replies, Markdown reflow, status rows and viewport resizing.
        snapshotFlow { Triple(listState.layoutInfo, isFollowingBottom, listState.isScrollInProgress || autoScrolling) }
            .collect { (layout, following, busy) ->
                if (following && !busy && layout.totalItemsCount > 0 && listState.canScrollForward) {
                    // Layout notifications can arrive during measurement; scroll on the next frame.
                    withFrameNanos { }
                    if (isFollowingBottom && !listState.isScrollInProgress) scrollToBottomNow()
                }
            }
    }

    private suspend fun scrollToBottomNow() {
        autoScrolling = true
        try {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex < 0) return
            if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) listState.scrollToItem(lastIndex)
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return
            val overflow = last.offset + last.size - layout.viewportEndOffset
            if (overflow > 0) listState.scrollBy(overflow.toFloat())
        } catch (_: CancellationException) {
            // User input may cancel one jump without disabling future following.
            currentCoroutineContext().ensureActive()
        } finally {
            autoScrolling = false
        }
    }
}
