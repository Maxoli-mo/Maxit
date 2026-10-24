package com.maximiliano.maxit.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.maximiliano.maxit.db.KaiDatabase
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ConversationDatabaseRecoveryTest {
    @Test
    fun `version one database migrates in place with its original messages intact`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE conversationEntity (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, type TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, shellTranscriptJson TEXT NOT NULL)", 0)
            driver.execute(null, "CREATE TABLE messageEntity (conversationId TEXT NOT NULL, orderIndex INTEGER NOT NULL, messageJson TEXT NOT NULL, PRIMARY KEY (conversationId, orderIndex))", 0)
            driver.execute(null, "INSERT INTO conversationEntity VALUES ('chat', 'Original', 'chat', 1, 2, '[]')", 0)
            driver.execute(null, "INSERT INTO messageEntity VALUES ('chat', 0, '{\"id\":\"message\",\"role\":\"user\",\"content\":\"Original message\"}')", 0)
            KaiDatabase.Schema.migrate(driver, 1, KaiDatabase.Schema.version)
            val database = KaiDatabase(driver)
            val persistence = SqlConversationPersistence(database, AppSettings(MapSettings()))
            val original = persistence.loadAll().single()
            assertEquals("Original message", original.messages.single().content)
            val updated = original.copy(messages = original.messages + Conversation.Message("large", "assistant", "x".repeat(1_100_000)))
            persistence.save(updated, listOf(updated))
            assertEquals(updated, SqlConversationPersistence(database, AppSettings(MapSettings())).loadAll().single())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `SQL failure midway through a checkpoint rolls back metadata and every message`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, schema = KaiDatabase.Schema)
        try {
            val database = KaiDatabase(driver)
            val app = AppSettings(MapSettings())
            val persistence = SqlConversationPersistence(database, app)
            val storage = ConversationStorage(app, persistence)
            val original = Conversation("chat", listOf(Conversation.Message("one", "user", "accepted"), Conversation.Message("two", "assistant", "old")), 1, 2, "Original")
            storage.saveConversation(original)
            driver.execute(null, "CREATE TRIGGER injected_failure BEFORE INSERT ON messageEntity WHEN NEW.messageJson LIKE '%INJECT_FAILURE%' BEGIN SELECT RAISE(ABORT, 'injected write failure'); END", 0)
            val replacement = original.copy(title = "Should roll back", messages = listOf(original.messages.first().copy(content = "changed"), original.messages.last().copy(content = "INJECT_FAILURE")))
            assertFails { storage.saveConversation(replacement) }
            assertEquals(original, storage.conversations.value.single())
            assertEquals(original, SqlConversationPersistence(database, app).loadAll().single())
            driver.execute(null, "DROP TRIGGER injected_failure", 0)
            storage.saveConversation(replacement)
            assertEquals(replacement, SqlConversationPersistence(database, app).loadAll().single())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `each durable transition restores without replaying an external action`() {
        for (transition in 0..4) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, schema = KaiDatabase.Schema)
            try {
                val database = KaiDatabase(driver)
                val app = AppSettings(MapSettings())
                val storage = ConversationStorage(app, SqlConversationPersistence(database, app))
                val messages = mutableListOf(Conversation.Message("user", "user", "Run task"), Conversation.Message("marker", "assistant", "", runId = "run", state = MessageState.RUNNING, isStatusMessage = true))
                if (transition >= 1) messages.add(Conversation.Message("partial", "assistant", "Received text", runId = "run", state = MessageState.GENERATING))
                if (transition >= 2) messages.add(Conversation.Message("intent", "assistant", "", toolCalls = listOf(ToolCallInfo("call", "compose_email", "{}", dispatched = transition >= 3)), runId = "run"))
                if (transition >= 4) messages.add(Conversation.Message("receipt", "tool", "Confirmed completed action", toolCallId = "call", toolName = "compose_email", runId = "run"))
                storage.saveConversation(Conversation("chat", messages, 1, 2))
                // A fresh store/decoder reads the committed database; recovery only records outcomes.
                val restarted = ConversationStorage(app, SqlConversationPersistence(database, app))
                restarted.loadConversations()
                val recovered = restarted.conversations.value.single().messages
                assertEquals("Run task", recovered.first().content)
                assertEquals(MessageState.INTERRUPTED, recovered.single { it.id == "marker" }.state)
                if (transition >= 1) assertEquals("Received text", recovered.single { it.id == "partial" }.content)
                if (transition == 2) assertTrue(recovered.single { it.toolCallId == "call" }.content.contains("not_started"))
                if (transition == 3) assertTrue(recovered.single { it.toolCallId == "call" }.content.contains("outcome_unknown"))
                if (transition == 4) assertEquals("Confirmed completed action", recovered.single { it.toolCallId == "call" }.content)
            } finally {
                driver.close()
            }
        }
    }
}
