package com.maximiliano.maxit.data

import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationReliabilityTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `stale approval clicks cannot approve a reused call ID in another conversation`() = runTest {
        val controller = ToolApprovalController()
        val request = ToolApprovalRequest("reused-provider-id", "first-chat", "external_action", "{}")
        val first = backgroundScope.async { controller.await(request) }
        runCurrent()
        val staleToken = controller.requests.value.single().id
        first.cancel()
        first.join()
        val second = backgroundScope.async { controller.await(request.copy(conversationId = "second-chat", arguments = "{\"target\":\"different\"}")) }
        runCurrent()
        val currentToken = controller.requests.value.single().id
        assertFalse(staleToken == currentToken)
        controller.resolve(staleToken, true)
        runCurrent()
        assertFalse(second.isCompleted)
        controller.resolve(currentToken, true)
        assertTrue(second.await())
        assertTrue(controller.requests.value.isEmpty())
    }

    private fun conversation(messages: List<Conversation.Message>) = Conversation("chat", messages, 1, 2, "Test")

    @Test
    fun `all message metadata and legacy attachments survive storage conversion`() {
        val original = Conversation.Message(
            id = "assistant", role = "assistant", content = "partial", runId = "run", state = MessageState.INTERRUPTED,
            toolCalls = listOf(ToolCallInfo("call", "fetch_url", "{}", thoughtSignature = "opaque", dispatched = true)),
            providerBlocks = Json.parseToJsonElement("""[{"type":"thinking","signature":"opaque","thinking":"private"}]""") as kotlinx.serialization.json.JsonArray,
            providerKey = "provider:model", reasoningContent = "reasoning", superseded = true,
            data = "aGk=", mimeType = "text/plain", fileName = "old.txt",
        )
        val roundTrip = original.toHistory().toStoredMessage()
        assertEquals(original.toolCalls, roundTrip.toolCalls)
        assertEquals(original.providerBlocks, roundTrip.providerBlocks)
        assertEquals(original.providerKey, roundTrip.providerKey)
        assertEquals(original.state, roundTrip.state)
        assertTrue(roundTrip.superseded)
        assertEquals("aGk=", roundTrip.attachments.single().data)
    }

    @Test
    fun `restart records unknown dispatched actions and not started proposals without erasing partial text`() {
        val entries = listOf(
            History(id = "marker", role = History.Role.ASSISTANT, content = "", isStatusMessage = true, state = MessageState.RUNNING),
            History(id = "partial", role = History.Role.ASSISTANT, content = "Saved partial", state = MessageState.GENERATING),
            History(role = History.Role.ASSISTANT, content = "", toolCalls = listOf(ToolCallInfo("done", "read_email", "{}", dispatched = true), ToolCallInfo("unknown", "compose_email", "{}", dispatched = true), ToolCallInfo("queued", "compose_email", "{}")).toImmutableList()),
            History(role = History.Role.TOOL, content = "Completed result", toolCallId = "done", toolName = "read_email"),
        )
        val recovered = settleInterruptedHistory(entries, MessageState.INTERRUPTED, "Stopped")
        assertEquals("Saved partial", recovered.single { it.id == "partial" }.content)
        assertEquals(MessageState.INTERRUPTED, recovered.single { it.id == "partial" }.state)
        assertEquals(1, recovered.count { it.toolCallId == "done" })
        assertTrue(recovered.single { it.toolCallId == "unknown" }.content.contains("outcome_unknown"))
        assertTrue(recovered.single { it.toolCallId == "queued" }.content.contains("not_started"))
        assertEquals(recovered, settleInterruptedHistory(recovered, MessageState.INTERRUPTED, "Stopped"))
    }

    @Test
    fun `failed commits never publish unsaved text or allow the following action`() = runTest {
        var fail = false
        var stored = emptyList<History>()
        var visible = emptyList<History>()
        var action = false
        val run = ConversationRun("run", "chat", emptyList(), persist = {
            if (fail) error("quota")
            stored = it
        }, publish = { visible = it })
        withContext(run) {
            run.update { it + History(role = History.Role.USER, content = "accepted") }
            fail = true
            assertFailsWith<ConversationWriteException> {
                run.update { it + History(role = History.Role.ASSISTANT, content = "not saved") }
                action = true
            }
        }
        assertFalse(action)
        assertEquals(stored, visible)
        assertEquals(listOf("accepted"), visible.map { it.content })
    }

    @Test
    fun `stream uses one durable identity and interruption flushes the received tail`() = runTest {
        var stored = emptyList<History>()
        val run = ConversationRun("run", "chat", emptyList(), persist = { stored = it }, publish = {})
        withContext(run) {
            val stream = StreamedAssistant(run.history, this)
            stream.append("First")
            assertEquals("First", stored.single().content)
            stream.append(" tail")
            stream.preservePartial()
            run.finish(MessageState.INTERRUPTED, "Stopped")
            assertEquals(stream.id, stored.single().id)
            assertEquals("First tail", stored.single().content)
            assertEquals(MessageState.INTERRUPTED, stored.single().state)
            assertFailsWith<CancellationException> { run.update { it + History(role = History.Role.USER, content = "late callback") } }
        }
    }

    @Test
    fun `native streamed answer completes after its recorded tools`() = runTest {
        val run = ConversationRun("run", "chat", emptyList(), persist = {}, publish = {})
        withContext(run) {
            val stream = StreamedAssistant(run.history, this)
            stream.append("Checking")
            run.update { it + History(role = History.Role.TOOL, content = "result", toolCallId = "call") }
            stream.complete(History(role = History.Role.ASSISTANT, content = "Checking. Done."))
            assertEquals(stream.id, run.history.value.last().id)
        }
    }

    @Test
    fun `stream timer flushes a quiet tail and bounds checkpoint frequency`() = runTest {
        var writes = 0
        var stored = emptyList<History>()
        val run = ConversationRun("run", "chat", emptyList(), persist = {
            stored = it
            writes++
        }, publish = {})
        withContext(run) {
            val stream = StreamedAssistant(run.history, this)
            stream.append("First")
            repeat(100) { stream.append("x".repeat(1024)) }
            assertEquals(1, writes)
            delay(101)
            assertEquals(2, writes)
            assertEquals(5 + 100 * 1024, stored.single().content.length)
            stream.preservePartial()
        }
    }

    @Test
    fun `a failed timed checkpoint cancels execution and never publishes its unsaved tail`() = runTest {
        var fail = false
        var visible = emptyList<History>()
        var nextAction = false
        val run = ConversationRun("run", "chat", emptyList(), persist = { if (fail) error("quota") }, publish = { visible = it })
        assertFailsWith<ConversationWriteException> {
            withContext(run) {
                val stream = StreamedAssistant(run.history, this)
                stream.append("Committed")
                fail = true
                stream.append(" unsaved")
                try {
                    delay(101)
                    nextAction = true
                } finally {
                    stream.preservePartial()
                }
            }
        }
        assertFalse(nextAction)
        assertEquals("Committed", visible.single().content)
    }

    @Test
    fun `an explicit import can restore a deleted chat but fences old runs before loading`() {
        val app = AppSettings(MapSettings())
        val store = ConversationStorage(app, SettingsConversationPersistence(app))
        store.loadConversations()
        val original = conversation(listOf(Conversation.Message("message", "user", "restored")))
        store.saveConversation(original)
        val oldGeneration = store.generation()
        store.deleteConversation(original.id)
        app.setConversationsJson(ConversationJson.encodeToString(ConversationsData.serializer(), ConversationsData(conversations = listOf(original))))
        assertFailsWith<CancellationException> { store.saveConversation(original, oldGeneration) }
        store.loadConversations()
        assertFailsWith<CancellationException> { store.saveConversation(original, oldGeneration) }
        store.saveConversation(original.copy(title = "New owner"), store.generation())
        assertEquals("New owner", store.conversations.value.single().title)
    }

    @Test
    fun `settings failures at each commit boundary keep the previous manifest and UI snapshot`() {
        for (boundary in 1..3) {
            val backing = MapSettings()
            var writes = 0
            var failAt = Int.MAX_VALUE
            val settings = object : Settings by backing {
                override fun putString(key: String, value: String) {
                    writes++
                    if (writes == failAt) error("injected quota")
                    backing.putString(key, value)
                }
            }
            val app = AppSettings(settings)
            val storage = ConversationStorage(app, SettingsConversationPersistence(app))
            val original = conversation(listOf(Conversation.Message("message", "assistant", "committed")))
            storage.saveConversation(original)
            failAt = writes + boundary
            assertFailsWith<IllegalStateException> { storage.saveConversation(original.copy(messages = listOf(original.messages.single().copy(content = "replacement")))) }
            assertEquals(original, storage.conversations.value.single())
            failAt = Int.MAX_VALUE
            val reloaded = ConversationStorage(app, SettingsConversationPersistence(app))
            reloaded.loadConversations()
            assertEquals(original, reloaded.conversations.value.single())
            assertEquals(1, backing.keys.count { it.startsWith("conversation_message_") })
        }
    }

    @Test
    fun `settings checkpoints reclaim obsolete blobs instead of filling quota quadratically`() {
        val backing = MapSettings()
        val app = AppSettings(backing)
        val storage = ConversationStorage(app, SettingsConversationPersistence(app))
        repeat(40) { storage.saveConversation(conversation(listOf(Conversation.Message("message", "assistant", "x".repeat(it + 1))))) }
        assertEquals(1, backing.keys.count { it.startsWith("conversation_message_") })
        assertEquals(1, backing.keys.count { it.startsWith("conversation_snapshot_") })
    }

    @Test
    fun `reload settles unfinished runs once while subsequent refresh cannot interrupt a live run`() {
        val app = AppSettings(MapSettings())
        val storage = ConversationStorage(app, SettingsConversationPersistence(app))
        val running = conversation(listOf(Conversation.Message("message", "assistant", "partial", state = MessageState.GENERATING)))
        storage.saveConversation(running)
        storage.loadConversations()
        assertEquals(MessageState.INTERRUPTED, storage.conversations.value.single().messages.single().state)
        storage.saveConversation(running)
        storage.loadConversations()
        assertEquals(MessageState.GENERATING, storage.conversations.value.single().messages.single().state)
    }

    @Test
    fun `deleted chats and replaced storage reject stale run writes`() {
        val app = AppSettings(MapSettings())
        val storage = ConversationStorage(app, SettingsConversationPersistence(app))
        storage.loadConversations()
        val original = conversation(listOf(Conversation.Message("message", "user", "saved")))
        storage.saveConversation(original)
        val generation = storage.generation()
        app.setConversationsJson(ConversationJson.encodeToString(ConversationsData.serializer(), ConversationsData(conversations = emptyList())))
        storage.loadConversations()
        assertFailsWith<CancellationException> { storage.saveConversation(original, generation) }
        storage.saveConversation(original)
        storage.deleteConversation(original.id)
        assertFailsWith<CancellationException> { storage.saveConversation(original) }
        assertTrue(storage.conversations.value.isEmpty())
    }

    @Test
    fun `more than twenty exchanges remain in durable history`() {
        val app = AppSettings(MapSettings())
        val storage = ConversationStorage(app, SettingsConversationPersistence(app))
        val messages = List(100) { Conversation.Message("$it", if (it % 2 == 0) "user" else "assistant", "message $it") }
        storage.saveConversation(conversation(messages))
        val reloaded = ConversationStorage(app, SettingsConversationPersistence(app))
        reloaded.loadConversations()
        assertEquals(messages, reloaded.conversations.value.single().messages)
    }

    @Test
    fun `scheduled occurrences are claimed once and interrupted claims pause after restart`() = runTest {
        val app = AppSettings(MapSettings())
        val store = TaskStore(app)
        val task = store.addTask("Task", "Send a report", 1)
        assertTrue(store.claimTask(task.id))
        assertFalse(store.claimTask(task.id))
        assertTrue(store.getDueTasks().isEmpty())
        val reloaded = TaskStore(app)
        reloaded.recoverInterruptedTasks()
        assertEquals(TaskStatus.PAUSED, reloaded.getAllTasks().single().status)
        assertTrue(reloaded.getDueTasks().isEmpty())
        reloaded.resumeTask(task.id)
        assertEquals(TaskStatus.PENDING, reloaded.getAllTasks().single().status)
        assertTrue(reloaded.claimTask(task.id))
    }
}
