package com.maximiliano.maxit.data

import com.maximiliano.maxit.ui.chat.History
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class ConversationWriteException(cause: Exception) : Exception("Could not save the conversation. Execution was stopped to avoid losing action history.", cause)
internal class RunPausedException(message: String) : Exception(message)

internal class ConversationInputAccepted(val notify: () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationInputAccepted>
}

/** One run's history. A commit always precedes publication or the next external action. */
internal class ConversationRun(
    val id: String,
    val conversationId: String,
    initial: List<History>,
    val interactive: Boolean = false,
    private val persist: (List<History>) -> Unit,
    private val publish: (List<History>) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationRun>
    val history = MutableStateFlow(initial)
    private val mutex = Mutex()
    private var closed = false

    suspend fun update(transform: (List<History>) -> List<History>) {
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            if (closed) throw CancellationException("Run is no longer active")
            val old = history.value
            val known = old.mapTo(hashSetOf()) { it.id }
            val next = transform(old).map { if (it.id !in known && it.runId == null) it.copy(runId = id) else it }
            commit(next)
        }
    }

    suspend fun finish(state: MessageState, reason: String) = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                if (history.value.any { it.runId == id }) commit(settleInterruptedHistory(history.value, state, reason))
            }
        }
    }

    private fun commit(next: List<History>) {
        try {
            persist(next.filter { it.role != History.Role.TOOL_EXECUTING })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ConversationWriteException(e)
        }
        history.value = next
        publish(next)
    }
}

internal suspend fun updateRunHistory(history: MutableStateFlow<List<History>>, transform: (List<History>) -> List<History>) {
    val run = coroutineContext[ConversationRun]
    if (run != null && run.history === history) run.update(transform) else history.value = transform(history.value)
}

internal suspend fun markToolDispatched(history: MutableStateFlow<List<History>>, callId: String) {
    updateRunHistory(history) { entries ->
        entries.map { entry ->
            if (entry.toolCalls.orEmpty().any { it.id == callId }) {
                entry.copy(toolCalls = entry.toolCalls.orEmpty().map { if (it.id == callId) it.copy(dispatched = true) else it }.toImmutableList())
            } else {
                entry
            }
        }
    }
}
