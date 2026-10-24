// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.data

import com.maximiliano.maxit.TerminalLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

expect fun readLegacyConversationFile(): ByteArray?
expect fun deleteLegacyConversationFile()

private const val MAX_SHELL_TRANSCRIPT_CHARS = 10_000

class ConversationStorage(
    private val appSettings: AppSettings,
    private val persistence: ConversationPersistence,
) {
    private val lock = ConversationLock()
    private val deletedIds = mutableSetOf<String>()
    private var loaded = false
    private var generation = 0L
    internal fun generation(): Long = lock.locked { generation }
    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = mutableConversations.asStateFlow()

    fun loadConversations() = lock.locked {
        if (loaded && appSettings.getConversationsJson() == null) return@locked
        generation++
        val original = persistence.loadAll()
        val loaded = original.map { conversation ->
            if (conversation.messages.any { it.state == MessageState.RUNNING || it.state == MessageState.GENERATING }) {
                conversation.copy(
                    messages = settleInterruptedHistory(
                        conversation.messages.map { it.toHistory() },
                        MessageState.INTERRUPTED,
                        "Interrupted when the app closed. Completed actions are preserved; verify unknown outcomes before repeating them.",
                    ).map { it.toStoredMessage() },
                )
            } else {
                conversation
            }
        }
        loaded.forEachIndexed { index, conversation ->
            if (conversation != original[index]) persistence.save(conversation, loaded)
        }
        deletedIds.clear()
        mutableConversations.value = loaded
        if (loaded.isEmpty()) migrateLegacy()
        this.loaded = true
    }

    fun saveConversation(conversation: Conversation, expectedGeneration: Long? = null) = lock.locked {
        if (appSettings.getConversationsJson() != null || (expectedGeneration != null && expectedGeneration != generation)) throw CancellationException("Conversation storage was replaced; the old run cannot write into it")
        if (conversation.id in deletedIds) throw CancellationException("Conversation was deleted")
        val current = mutableConversations.value
        val index = current.indexOfFirst { it.id == conversation.id }
        val existing = current.getOrNull(index)
        val saved = if (conversation.shellTranscript.isEmpty() && existing?.shellTranscript?.isNotEmpty() == true) {
            conversation.copy(shellTranscript = existing.shellTranscript)
        } else {
            conversation
        }
        val next = if (index >= 0) current.toMutableList().apply { set(index, saved) } else current + saved
        persistence.save(saved, next)
        mutableConversations.value = next
    }

    /**
     * Persist a shell transcript snapshot for [conversationId]. No-op when the
     * conversation isn't in the saved set yet (chat hasn't been saved). The
     * stored transcript is trimmed from the head until the joined text is
     * within the per-conversation char budget — older lines drop first so the
     * tail (most recent activity) survives a restart.
     */
    fun updateShellTranscript(conversationId: String, lines: List<TerminalLine>, expectedGeneration: Long? = null) = lock.locked {
        if (appSettings.getConversationsJson() != null || (expectedGeneration != null && expectedGeneration != generation)) return@locked
        val trimmed = trimToCharLimit(lines, MAX_SHELL_TRANSCRIPT_CHARS)
        val current = mutableConversations.value
        val idx = current.indexOfFirst { it.id == conversationId }
        if (idx >= 0 && current[idx].shellTranscript != trimmed) {
            val updated = current[idx].copy(shellTranscript = trimmed)
            val next = current.toMutableList().apply { set(idx, updated) }
            persistence.saveShellTranscript(updated, next)
            mutableConversations.value = next
        }
    }

    private fun trimToCharLimit(lines: List<TerminalLine>, limit: Int): List<TerminalLine> {
        if (lines.isEmpty()) return lines
        val totalChars = lines.sumOf { it.text.length }
        if (totalChars <= limit) return lines
        val tail = ArrayDeque<TerminalLine>()
        var running = 0
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (running + line.text.length <= limit) {
                tail.addFirst(line)
                running += line.text.length
            } else {
                break
            }
        }
        if (tail.isNotEmpty()) return tail.toList()
        // Single most-recent line is itself larger than the budget — keep its
        // tail so the user still sees something on next launch.
        val last = lines.last()
        return listOf(last.withText(last.text.takeLast(limit)))
    }

    fun deleteConversation(id: String) = lock.locked {
        val next = mutableConversations.value.filter { it.id != id }
        persistence.delete(id, next)
        deletedIds.add(id)
        mutableConversations.value = next
    }

    private fun migrateLegacy() {
        val legacyData = readLegacyConversationFile() ?: return
        val key = appSettings.getEncryptionKey() ?: error("Legacy conversation encryption key is missing; the original file was preserved")
        check(key.isNotEmpty()) { "Legacy conversation encryption key is empty; the original file was preserved" }
        val decrypted = ByteArray(legacyData.size)
        for (i in legacyData.indices) {
            decrypted[i] = (legacyData[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val conversations = try {
            ConversationJson.decodeFromString<ConversationsData>(decrypted.decodeToString()).conversations
        } catch (e: Exception) {
            throw IllegalStateException("Legacy conversations could not be decoded; the original file was preserved", e)
        }
        persistence.replaceAll(conversations)
        mutableConversations.value = conversations
        deleteLegacyConversationFile()
    }
}
