package com.maximiliano.maxit.sandbox

import com.maximiliano.maxit.TerminalLine
import com.maximiliano.maxit.data.ConversationStorage

/** A session keeps its original generation even after an import retains its conversation ID. */
internal class SessionTranscriptOwner(
    private val storage: ConversationStorage,
    private val sessionId: String,
) {
    private val generation = storage.generation()

    fun isCurrent(): Boolean = generation == storage.generation()

    fun save(lines: List<TerminalLine>) {
        storage.updateShellTranscript(sessionId, lines, generation)
    }
}
