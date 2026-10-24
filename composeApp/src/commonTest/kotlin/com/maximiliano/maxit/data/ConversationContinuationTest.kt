package com.maximiliano.maxit.data

import com.maximiliano.maxit.data.providers.toResponsesInput
import com.maximiliano.maxit.network.readChatCompletionStream
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.testutil.FakeDataRepository
import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import com.maximiliano.maxit.ui.chat.toGroqMessageDto
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.utils.io.ByteReadChannel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationContinuationTest {
    private val call = ToolCallInfo("durable-call", "fetch_url", "{}", providerCallId = "native-call")
    private fun proposal() = History(role = History.Role.ASSISTANT, content = "", providerKey = "origin", toolCalls = persistentListOf(call))
    private fun receipt(content: String, runId: String? = null) = History(role = History.Role.TOOL, content = content, toolCallId = call.id, toolName = call.name, runId = runId)

    @Test
    fun `wire aliases preserve native identities without changing durable evidence`() {
        val original = listOf(proposal(), receipt("result"))
        val wire = compatibleToolHistory(original, setOf(call.name), "origin")
        assertEquals("native-call", wire.first().toolCalls!!.single().id)
        assertEquals("native-call", wire.last().toolCallId)
        assertEquals("durable-call", original.first().toolCalls!!.single().id)
        assertEquals("durable-call", compatibleToolHistory(original, setOf(call.name), "different").first().toolCalls!!.single().id)
    }

    @Test
    fun `denial applies only to the same invocation and run and quoted statuses are not receipts`() {
        val history = listOf(proposal(), receipt("""{"success":false,"status":"denied"}""", "attempt"))
        assertTrue(hasDeniedInvocation(history, call.name, call.arguments, "attempt"))
        assertTrue(!hasDeniedInvocation(history, call.name, """{"different":true}""", "attempt"))
        assertTrue(!hasDeniedInvocation(history, call.name, call.arguments, "later"))
        assertTrue(!hasUnknownOutcome(listOf(proposal(), receipt("Quoted \"outcome_unknown\" text")), call.name, call.arguments))
    }

    @Test
    fun `six distinct failures allow one different recovery but never force another call after a final answer`() {
        val guard = ToolLoopGuard()
        repeat(6) { index ->
            val next = call.copy(id = "$index", arguments = """{"index":$index}""")
            assertNull(guard.check(listOf(next), emptyList()))
            guard.record(listOf(next), listOf(Triple(next.id, next.name, """{"success":false,"error":"failed"}""")))
        }
        val recovery = call.copy(arguments = """{"index":6}""")
        assertNotNull(guard.check(listOf(recovery), emptyList()))
        guard.record(listOf(recovery), listOf(Triple(recovery.id, recovery.name, """{"success":false}""")))
        assertFailsWith<RunPausedException> { guard.check(listOf(call.copy(arguments = """{"index":7}""")), emptyList()) }
        assertNull(guard.check(emptyList(), emptyList()))
    }

    @Test
    fun `the history tool retrieves complete oversized evidence in bounded slices by call ID`() = runTest {
        val payload = "x".repeat(20_000) + "😀 tail"
        val tool = conversationHistoryTool(MutableStateFlow(listOf(proposal(), receipt(payload))))
        val recovered = StringBuilder()
        var offset: Int? = 0
        while (offset != null) {
            val page = tool.execute(mapOf("message_id" to call.id, "char_offset" to offset, "max_chars" to 1000)) as Map<*, *>
            val content = page["content"] as String
            assertTrue(content.length <= 1000)
            recovered.append(content)
            offset = page["next_char_offset"] as? Int
        }
        assertEquals(payload, recovered.toString())
    }

    @Test
    fun `fragmented gateway reasoning retains encrypted data and opaque fields`() = runTest {
        val response = readChatCompletionStream(
            ByteReadChannel(
                listOf(
                    """{"choices":[{"delta":{"reasoning_details":[{"index":0,"type":"reasoning.encrypted","data":"first","future":"opaque"}]}}]}""",
                    """{"choices":[{"delta":{"content":"Done","reasoning_details":[{"index":0,"data":"second"}]},"finish_reason":"stop"}]}""",
                    "[DONE]",
                ).joinToString("\n\n", postfix = "\n\n") { "data: $it" },
            ),
        ) {}
        val block = response.choices.single().message!!.reasoningDetails!!.single() as JsonObject
        assertEquals("firstsecond", block["data"]!!.jsonPrimitive.content)
        assertEquals("opaque", block["future"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Responses replays complete encrypted continuation and PDF inputs without flattening them`() {
        val raw = Json.parseToJsonElement("""[{"type":"reasoning","id":"r","encrypted_content":"opaque"},{"type":"function_call","id":"fc","call_id":"native-call","name":"fetch_url","arguments":"{}"}]""") as JsonArray
        val projected = compatibleToolHistory(listOf(proposal().copy(providerBlocks = raw), receipt("result")), setOf(call.name), "origin")
        val output = toResponsesInput(projected.map { it.toGroqMessageDto() })
        assertEquals(raw[0], output[0])
        assertEquals(raw[1], output[1])
        assertEquals("native-call", output[2]["call_id"]!!.jsonPrimitive.content)
        val user = History(role = History.Role.USER, content = "Read", attachments = persistentListOf(Attachment("BASE64", "application/pdf", "document.pdf")))
        val pdf = (toResponsesInput(listOf(user.toGroqMessageDto(supportsPdf = true))).single()["content"] as JsonArray).last() as JsonObject
        assertEquals("input_file", pdf["type"]!!.jsonPrimitive.content)
        assertEquals("data:application/pdf;base64,BASE64", pdf["file_data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `background storage failure is surfaced and cannot dispatch an unclaimed task`() = runTest {
        val backing = MapSettings()
        var fail = false
        val settings = object : Settings by backing {
            override fun putString(key: String, value: String) {
                if (fail) error("quota")
                backing.putString(key, value)
            }
        }
        val app = AppSettings(settings)
        app.setSchedulingEnabled(true)
        val tasks = TaskStore(app)
        tasks.addTask("Task", "Review work", 1)
        val repository = FakeDataRepository()
        val scheduler = TaskScheduler(repository, taskStore = tasks, appSettings = app, backgroundDispatcher = StandardTestDispatcher(testScheduler))
        fail = true
        scheduler.start()
        delay(60_001)
        assertNotNull(scheduler.executionError.value)
        assertTrue(repository.askCalls.isEmpty())
        assertEquals(TaskStatus.PENDING, tasks.getAllTasks().single().status)
    }
}
