package com.maximiliano.maxit.sandbox

import com.maximiliano.maxit.TerminalLine
import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.Conversation
import com.maximiliano.maxit.data.ConversationJson
import com.maximiliano.maxit.data.ConversationStorage
import com.maximiliano.maxit.data.ConversationsData
import com.maximiliano.maxit.data.SettingsConversationPersistence
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTranscriptOwnerTest {
    @Test
    fun `retained conversation ID gets a fresh owner while late old callbacks stay fenced`() {
        val app = AppSettings(MapSettings())
        val storage = ConversationStorage(app, SettingsConversationPersistence(app))
        storage.loadConversations()
        val original = Conversation(id = "chat", messages = emptyList(), createdAt = 1L, updatedAt = 2L)
        storage.saveConversation(original)
        val old = SessionTranscriptOwner(storage, original.id)
        old.save(listOf(TerminalLine.Output("old session")))
        val imported = original.copy(shellTranscript = listOf(TerminalLine.Output("imported")))
        app.setConversationsJson(ConversationJson.encodeToString(ConversationsData(conversations = listOf(imported))))
        old.save(listOf(TerminalLine.Output("late before reload")))
        storage.loadConversations()
        assertFalse(old.isCurrent())
        assertEquals(imported.shellTranscript, storage.conversations.value.single().shellTranscript)

        val fresh = SessionTranscriptOwner(storage, original.id)
        assertTrue(fresh.isCurrent())
        val newOutput = imported.shellTranscript + TerminalLine.Output("fresh command")
        fresh.save(newOutput)
        old.save(listOf(TerminalLine.Output("late after reset")))
        assertEquals(newOutput, storage.conversations.value.single().shellTranscript)
        assertEquals(newOutput, SettingsConversationPersistence(app).loadAll().single().shellTranscript)
    }
}
