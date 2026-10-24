package com.maximiliano.maxit.data

import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationContextAndLoopTest {
    private fun call(name: String = "read_email", args: String = "{}") = ToolCallInfo("call-$name-$args", name, args)
    private fun batch(call: ToolCallInfo) = History(role = History.Role.ASSISTANT, content = "", toolCalls = listOf(call).toImmutableList(), providerKey = "original")
    private fun receipt(call: ToolCallInfo, result: String) = History(role = History.Role.TOOL, content = result, toolCallId = call.id, toolName = call.name)

    @Test
    fun `useful execution can exceed fifteen rounds without a replacement round ceiling`() {
        val guard = ToolLoopGuard()
        repeat(200) { index ->
            val next = call(args = """{"message":$index}""")
            assertNull(guard.check(listOf(next), emptyList()))
            guard.record(listOf(next), listOf(Triple(next.id, next.name, "result $index")))
        }
    }

    @Test
    fun `equivalent JSON keys detect repetition and give one recovery before a durable pause`() {
        val guard = ToolLoopGuard()
        val first = call(args = """{"b":2,"a":1}""")
        val reordered = call(args = """{"a":1,"b":2}""")
        repeat(2) { guard.record(listOf(first), listOf(Triple(first.id, first.name, "same"))) }
        assertNotNull(guard.check(listOf(reordered), emptyList()))
        assertFailsWith<RunPausedException> { guard.check(listOf(reordered), emptyList()) }
    }

    @Test
    fun `alternating failures are detected and history seeds the guard after Continue`() {
        val first = call(args = """{"message":"A"}""")
        val second = call(args = """{"message":"B"}""")
        val history = listOf(batch(first), receipt(first, "failure A"), batch(second), receipt(second, "failure B"), batch(first.copy(id = "a2")), receipt(first.copy(id = "a2"), "failure A"), batch(second.copy(id = "b2")), receipt(second.copy(id = "b2"), "failure B"))
        assertNotNull(ToolLoopGuard().check(listOf(first), history))
    }

    @Test
    fun `new user requests do not inherit completed requests loop evidence`() {
        val history = buildList {
            repeat(2) { index ->
                val next = call("load_skill", "{}").copy(id = "prior-$index")
                add(History(role = History.Role.USER, content = "Independent request $index"))
                add(batch(next))
                add(receipt(next, "same skill"))
                add(History(role = History.Role.ASSISTANT, content = "Finished"))
            }
            add(History(role = History.Role.USER, content = "New independent request"))
        }
        assertNull(ToolLoopGuard().check(listOf(call("load_skill")), history))
    }

    @Test
    fun `Continue seeds its request but regeneration excludes replaced rounds`() {
        val next = call("load_skill")
        val history = buildList {
            add(History(role = History.Role.USER, content = "Current task"))
            repeat(2) { index ->
                val repeated = next.copy(id = "round-$index")
                add(batch(repeated))
                add(receipt(repeated, "same result"))
            }
            add(History(role = History.Role.ASSISTANT, content = "Paused", isStatusMessage = true, state = MessageState.PAUSED))
        }
        assertNotNull(ToolLoopGuard().check(listOf(next), history))
        val replaced = history.map { if (it.role == History.Role.USER) it else it.copy(superseded = true) }
        assertNull(ToolLoopGuard().check(listOf(next), replaced))
    }

    @Test
    fun `running process polling backs off instead of being mistaken for a dead loop`() = runTest {
        val guard = ToolLoopGuard()
        val poll = call("manage_process", """{"action":"status","id":"job"}""")
        repeat(30) {
            assertNull(guard.check(listOf(poll), emptyList()))
            guard.record(listOf(poll), listOf(Triple(poll.id, poll.name, """{"status":"running"}""")))
        }
        val before = testScheduler.currentTime
        guard.backoffIfPolling(listOf(poll))
        assertTrue(testScheduler.currentTime - before >= 2000)
    }

    @Test
    fun `changing an array or string does not canonicalize distinct arguments together`() {
        assertEquals(canonicalToolJson("""{"b":2,"a":1}"""), canonicalToolJson("""{"a":1,"b":2}"""))
        assertFalse(canonicalToolJson("""[1,2]""") == canonicalToolJson("""[2,1]"""))
        assertFalse(canonicalToolJson("""{"value":"A"}""") == canonicalToolJson("""{"value":"a"}"""))
    }

    @Test
    fun `unknown outcomes require review even when argument key order changes`() {
        val original = call("compose_email", """{"body":"Hello","to":"person@example.invalid"}""")
        val history = listOf(batch(original), receipt(original, """{"status":"outcome_unknown"}"""))
        assertTrue(hasUnknownOutcome(history, original.name, """{"to":"person@example.invalid","body":"Hello"}"""))
        assertFalse(hasUnknownOutcome(history, original.name, """{"body":"Different","to":"person@example.invalid"}"""))
    }

    @Test
    fun `tools disabled and provider switches preserve tool evidence in valid text history`() {
        val signed = call().copy(thoughtSignature = "opaque")
        val history = listOf(batch(signed), receipt(signed, "Important completed result"))
        for (projected in listOf(compatibleToolHistory(history, emptySet()), compatibleToolHistory(history, setOf(signed.name), "different"))) {
            assertTrue(projected.all { it.role != History.Role.TOOL && it.toolCalls.isNullOrEmpty() })
            assertTrue(projected.single().content.contains("Important completed result"))
            assertTrue(projected.single().content.contains("already attempted"))
        }
        assertEquals(history, compatibleToolHistory(history, setOf(signed.name), "original"))
    }

    @Test
    fun `opaque Anthropic continuation blocks survive for the same provider`() {
        val blocks = Json.parseToJsonElement("""[{"type":"redacted_thinking","data":"opaque"}]""") as kotlinx.serialization.json.JsonArray
        val entry = batch(call()).copy(providerBlocks = blocks)
        val complete = listOf(entry, receipt(call(), "result"))
        assertEquals(blocks, compatibleToolHistory(complete, setOf("read_email"), "original").first().providerBlocks)
        assertNull(compatibleToolHistory(complete, setOf("read_email"), "different").single().providerBlocks)
        assertNull(compatibleToolHistory(listOf(entry), setOf("read_email"), "original").single().providerBlocks)
    }

    @Test
    fun `context reduction preserves the transcript and never separates a tool exchange`() {
        val history = mutableListOf<History>()
        repeat(70) { index ->
            val next = call(args = """{"message":$index}""")
            history.add(History(role = History.Role.USER, content = "request $index"))
            history.add(batch(next))
            history.add(receipt(next, "x".repeat(500)))
        }
        history.add(History(role = History.Role.USER, content = "Continue the task"))
        val original = history.toList()
        val projected = conversationContext(history, "Instructions", 8192)
        assertEquals(original, history)
        assertTrue(projected.size < history.size)
        assertTrue(projected.any { it.role == History.Role.USER && it.content == "Continue the task" })
        for (entry in projected.filter { it.role == History.Role.TOOL }) assertTrue(projected.any { row -> row.toolCalls.orEmpty().any { it.id == entry.toolCallId } })
    }

    @Test
    fun `superseded attempts retain action evidence without remaining active instructions`() {
        val next = call()
        val history = listOf(History(role = History.Role.USER, content = "OLD INSTRUCTION", superseded = true), batch(next).copy(superseded = true), receipt(next, "Prior action evidence").copy(superseded = true), History(role = History.Role.USER, content = "New request"))
        val projected = conversationContext(history, null, 8192)
        assertFalse(projected.any { it.role == History.Role.USER && it.content == "OLD INSTRUCTION" })
        assertTrue(projected.joinToString { it.content }.contains("Prior action evidence"))
        assertTrue(projected.any { it.role == History.Role.USER && it.content == "New request" })
    }
}
