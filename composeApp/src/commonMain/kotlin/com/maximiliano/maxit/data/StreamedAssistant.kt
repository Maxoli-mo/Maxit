package com.maximiliano.maxit.data

import com.maximiliano.maxit.network.StreamInterruptedException
import com.maximiliano.maxit.ui.chat.History
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Batch persistence, not fake typing: the UI observes only committed text. */
@OptIn(ExperimentalUuidApi::class)
internal class StreamedAssistant(private val history: MutableStateFlow<List<History>>, private val scope: CoroutineScope) {
    val id = Uuid.random().toString()
    private val text = StringBuilder()
    private val mutex = Mutex()
    private var flushed = 0
    private var closed = false
    private var failure: Exception? = null
    private var checkpoint: Job? = null

    suspend fun append(delta: String) = mutex.withLock {
        failure?.let { throw it }
        if (closed) throw CancellationException("Assistant turn has ended")
        if (text.length + delta.length > 8_388_608) throw StreamInterruptedException("The response exceeded the per-message safety size. Committed text was preserved.")
        text.append(delta)
        if (flushed == 0) {
            flushLocked()
        } else if (checkpoint == null && text.length > flushed) {
            // The timer belongs to the run, not the HTTP body scope, which must be able to return.
            checkpoint = scope.launch {
                delay(100)
                mutex.withLock {
                    checkpoint = null
                    if (!closed) {
                        try {
                            flushLocked()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failure = e
                            scope.coroutineContext[Job]?.cancel(CancellationException("Conversation checkpoint failed", e))
                        }
                    }
                }
            }
        }
    }

    private suspend fun flushLocked() {
        failure?.let { throw it }
        if (text.length == flushed) return
        val snapshot = text.toString()
        updateRunHistory(history) { entries ->
            val existing = entries.indexOfFirst { it.id == id }
            if (existing < 0) {
                entries + History(id = id, role = History.Role.ASSISTANT, content = snapshot, state = MessageState.GENERATING)
            } else {
                entries.toMutableList().apply { set(existing, entries[existing].copy(content = snapshot)) }
            }
        }
        flushed = text.length
    }

    suspend fun complete(message: History) {
        mutex.withLock {
            closed = true
            checkpoint?.cancel()
            failure?.let { throw it }
            updateRunHistory(history) { entries ->
                val existing = entries.indexOfFirst { it.id == id }
                val completed = message.copy(id = id, state = MessageState.COMPLETE)
                if (existing < 0) {
                    entries + completed
                } else {
                    val saved = completed.copy(runId = entries[existing].runId)
                    if (entries.drop(existing + 1).any { it.role == History.Role.TOOL }) {
                        entries.filterNot { it.id == id } + saved
                    } else {
                        entries.toMutableList().apply { set(existing, saved) }
                    }
                }
            }
            flushed = text.length
        }
        checkpoint?.cancelAndJoin()
    }

    suspend fun preservePartial() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            checkpoint?.cancel()
            flushLocked()
        }
        checkpoint?.cancelAndJoin()
    }
}
