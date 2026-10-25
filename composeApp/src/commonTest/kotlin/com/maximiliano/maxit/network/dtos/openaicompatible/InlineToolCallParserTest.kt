// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.network.dtos.openaicompatible

import com.maximiliano.maxit.network.tools.ParameterSchema
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.network.tools.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineToolCallParserTest {

    private val execShell = stubTool(
        name = "execute_shell_command",
        parameters = mapOf(
            "command" to "string",
            "timeout" to "integer",
        ),
    )

    @Test
    fun `passes through text without any tool_call`() {
        val extracted = extractInlineToolCalls("just a regular message", listOf(execShell))
        assertEquals("just a regular message", extracted.cleanedText)
        assertTrue(extracted.calls.isEmpty())
    }

    @Test
    fun `parses hermes style xml block and coerces integer timeout`() {
        val raw = """
            <tool_call>
            <function=execute_shell_command>
            <parameter=command>
            ssh hk-server "echo hi"
            </parameter>
            <parameter=timeout>
            180
            </parameter>
            </function>
            </tool_call>
        """.trimIndent()

        val extracted = extractInlineToolCalls(raw, listOf(execShell))

        assertEquals(1, extracted.calls.size)
        val call = extracted.calls[0]
        assertEquals("execute_shell_command", call.name)
        // timeout coerced to a JSON number; command stays a string
        assertTrue(call.arguments.contains("\"timeout\":180"), "expected unquoted integer, got: ${call.arguments}")
        assertTrue(call.arguments.contains("\"command\":\"ssh hk-server \\\"echo hi\\\"\""), "command not preserved: ${call.arguments}")
        assertEquals("", extracted.cleanedText)
    }

    @Test
    fun `parses json flavor with arguments field`() {
        val raw = """<tool_call>{"name":"execute_shell_command","arguments":{"command":"ls","timeout":5}}</tool_call>"""

        val extracted = extractInlineToolCalls(raw, listOf(execShell))

        assertEquals(1, extracted.calls.size)
        val call = extracted.calls[0]
        assertEquals("execute_shell_command", call.name)
        assertTrue(call.arguments.contains("\"command\":\"ls\""))
        assertTrue(call.arguments.contains("\"timeout\":5"))
        assertEquals("", extracted.cleanedText)
    }

    @Test
    fun `keeps unparseable block in cleaned text rather than dropping it`() {
        val raw = "intro <tool_call>not xml and not json</tool_call> tail"
        val extracted = extractInlineToolCalls(raw, listOf(execShell))
        assertTrue(extracted.calls.isEmpty())
        assertTrue(
            extracted.cleanedText.contains("<tool_call>"),
            "unparseable block should remain visible: ${extracted.cleanedText}",
        )
    }

    @Test
    fun `handles multiple inline tool calls in one message`() {
        val raw = """
            <tool_call>
            <function=execute_shell_command>
            <parameter=command>echo one</parameter>
            </function>
            </tool_call>

            <tool_call>
            <function=execute_shell_command>
            <parameter=command>echo two</parameter>
            </function>
            </tool_call>
        """.trimIndent()

        val extracted = extractInlineToolCalls(raw, listOf(execShell))

        assertEquals(2, extracted.calls.size)
        assertEquals("", extracted.cleanedText)
    }

    @Test
    fun `declared tool without a parameter type keeps string parameters`() {
        val raw = """<tool_call><function=mystery_tool><parameter=count>42</parameter></function></tool_call>"""
        val extracted = extractInlineToolCalls(raw, listOf(stubTool("mystery_tool", emptyMap())))
        assertEquals(1, extracted.calls.size)
        assertTrue(extracted.calls[0].arguments.contains("\"count\":\"42\""))
    }

    @Test
    fun `wrapped examples mixed text and incomplete or unknown batches remain unchanged`() {
        val xml = "<function=execute_shell_command><parameter=command>echo example</parameter></function>"
        val call = "<tool_call>$xml</tool_call>"
        for (text in listOf(
            "```xml\n$call\n```",
            "~~~xml\n$call\n~~~",
            "`$call`",
            "\"$call\"",
            "> $call",
            "Example: $call",
            "$call trailing prose",
            "{\"example\":\"$call\"}",
            "<tool_call>```xml\n$xml\n```</tool_call>",
            "<tool_call>\"$xml\"</tool_call>",
            "<tool_call>$call</tool_call>",
            "$call\n<tool_call>$xml",
            "$call\n<tool_call>not a call</tool_call>",
            "$call\n<tool_call><function=unknown></function></tool_call>",
        )) {
            val extracted = extractInlineToolCalls(text, listOf(execShell))
            assertTrue(extracted.calls.isEmpty(), text)
            assertEquals(text, extracted.cleanedText)
        }
        assertTrue(extractInlineToolCalls(call, emptyList()).calls.isEmpty())
    }

    @Test
    fun `bare tool envelope requests repair but is never extracted for execution`() {
        val raw = """{"tool_calls":[{"name":"load_skill","arguments":{"id":"example-skill"}}]}"""
        assertTrue(hasBareToolCallEnvelope(raw, setOf("load_skill")))
        assertTrue(extractInlineToolCalls(raw, listOf(execShell)).calls.isEmpty())
        assertEquals(raw, extractInlineToolCalls(raw, listOf(execShell)).cleanedText)
        assertTrue(hasBareToolCallEnvelope("\n $raw \n", setOf("load_skill")))
        assertTrue(hasBareToolCallEnvelope("""{"tool_calls":[{"id":"c","type":"function","function":{"name":"load_skill","arguments":"{}"}}]}""", setOf("load_skill")))
    }

    @Test
    fun `examples ordinary JSON unknown tools and malformed envelopes stay text`() {
        val raw = """{"tool_calls":[{"name":"load_skill","arguments":{}}]}"""
        for (text in listOf(
            "```json\n$raw\n```",
            "~~~json\n$raw\n~~~",
            "Example: $raw",
            "> $raw",
            "\"${raw.replace("\"", "\\\"")}\"",
            """{"example":true,"tool_calls":[{"name":"load_skill","arguments":{}}]}""",
            """{"tool_calls":[]}""",
            """{"tool_calls":[{"name":"unknown","arguments":{}}]}""",
            """{"tool_calls":[{"name":"load_skill","arguments":{},"description":"example"}]}""",
            """{"tool_calls":[{"name":"load_skill","arguments":null}]}""",
            """{"tool_calls":[{"name":"load_skill","arguments":[]}]}""",
            raw.dropLast(1),
        )) {
            assertFalse(hasBareToolCallEnvelope(text, setOf("load_skill")), text)
            assertTrue(extractInlineToolCalls(text, listOf(execShell)).calls.isEmpty(), text)
        }
        assertFalse(hasBareToolCallEnvelope(raw, emptySet()))
    }

    private fun stubTool(name: String, parameters: Map<String, String>): Tool = object : Tool {
        override val schema = ToolSchema(
            name = name,
            description = "",
            parameters = parameters.mapValues { (_, type) ->
                ParameterSchema(type = type, description = "", required = false)
            },
        )

        override suspend fun execute(args: Map<String, Any>): Any = ""
    }
}
