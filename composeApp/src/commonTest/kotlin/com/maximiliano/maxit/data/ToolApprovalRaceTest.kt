package com.maximiliano.maxit.data

import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.network.tools.ToolSchema
import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ToolApprovalRaceTest {
    @Test
    fun `a tool reenabled after missing approval lookup is rejected without real permission`() = runTest {
        var enabled = false
        var executions = 0
        val tool = object : Tool {
            override val schema = ToolSchema("write_file", "Approval requiring test action", emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                executions++
                return "executed"
            }
        }
        val executor = ToolExecutor { if (enabled) listOf(tool) else emptyList() }
        val run = ConversationRun("run", "conversation", emptyList(), interactive = true, persist = {}, publish = {})
        assertFalse(executor.requiresApproval(tool.schema.name, "{}"))
        enabled = true
        assertFailsWith<RunPausedException> { withContext(run) { executor.executeTool(tool.schema.name, "{}") } }
        assertEquals(0, executions)
    }

    @Test
    fun `an uncertain previous outcome requires approval even for an otherwise read oriented tool`() = runTest {
        var executions = 0
        val tool = object : Tool {
            override val schema = ToolSchema("get_local_time", "Read only test action", emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                executions++
                return "time"
            }
        }
        val executor = ToolExecutor { listOf(tool) }
        val history = listOf(
            History(role = History.Role.ASSISTANT, content = "", toolCalls = persistentListOf(ToolCallInfo("old", tool.schema.name, "{}", dispatched = true))),
            History(role = History.Role.TOOL, content = """{"status":"outcome_unknown","success":false}""", toolCallId = "old"),
        )
        val run = ConversationRun("run", "conversation", history, interactive = true, persist = {}, publish = {})
        assertFalse(executor.requiresApproval(tool.schema.name, "{}"))
        assertFailsWith<RunPausedException> { withContext(run) { executor.executeTool(tool.schema.name, "{}") } }
        assertEquals(0, executions)
    }

    @Test
    fun `exact approval is confined to its invocation and cannot authorize other arguments`() = runTest {
        var executions = 0
        val tool = object : Tool {
            override val schema = ToolSchema("write_file", "Approval requiring test action", emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                executions++
                return "executed"
            }
        }
        val executor = ToolExecutor { listOf(tool) }
        val run = ConversationRun("run", "conversation", emptyList(), interactive = true, persist = {}, publish = {})
        withContext(run) {
            withContext(ToolExecutionPermit(tool.schema.name, "{}")) {
                assertEquals("executed", executor.executeTool(tool.schema.name, "{}"))
                assertFailsWith<RunPausedException> { executor.executeTool(tool.schema.name, "{\"different\":true}") }
            }
            assertFailsWith<RunPausedException> { executor.executeTool(tool.schema.name, "{}") }
        }
        assertEquals(1, executions)
    }
}
