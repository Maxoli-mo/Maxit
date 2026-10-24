package com.maximiliano.maxit.network

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.Conversation
import com.maximiliano.maxit.data.ConversationWriteException
import com.maximiliano.maxit.data.SettingsConversationPersistence
import com.maximiliano.maxit.data.StreamedAssistant
import com.maximiliano.maxit.data.toStoredMessage
import com.maximiliano.maxit.network.dtos.gemini.GeminiChatResponseDto
import com.maximiliano.maxit.network.dtos.gemini.extractText
import com.maximiliano.maxit.ui.chat.History
import com.russhwolf.settings.MapSettings
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatStreamingTest {
    private fun events(vararg data: String) = ByteReadChannel(data.joinToString("\n\n", postfix = "\n\n") { "data: $it" })

    @Test
    fun `SSE handles CRLF comments and multiline data`() = runTest {
        val received = mutableListOf<Pair<String?, String>>()
        readSse(ByteReadChannel(": keepalive\r\nevent: chunk\r\ndata: first\r\ndata: second\r\n\r\n")) { type, data -> received.add(type to data) }
        assertEquals(listOf<Pair<String?, String>>("chunk" to "first\nsecond"), received)
    }

    @Test
    fun `chat completions emit text immediately and assemble fragmented tool arguments`() = runTest {
        val received = mutableListOf<String>()
        val response = readChatCompletionStream(
            events(
                """{"choices":[{"index":0,"delta":{"content":"Checking "}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"now","tool_calls":[{"index":0,"id":"call","function":{"name":"fetch_url","arguments":"{\"url\":"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"https://example.invalid\"}"}}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        ) { received.add(it) }
        assertEquals(listOf("Checking ", "now"), received)
        val message = requireNotNull(response.choices.single().message)
        assertEquals("Checking now", message.effectiveContent)
        assertEquals("""{"url":"https://example.invalid"}""", message.toolCalls!!.single().function.arguments)
    }

    @Test
    fun `refusals with absent null or empty content match regular JSON and remain nonempty`() = runTest {
        for (content in listOf("", "\"content\":null,", "\"content\":\"\",", "\"content\":[],")) {
            val received = StringBuilder()
            val response = readChatCompletionStream(
                events(
                    """{"choices":[{"delta":{$content"refusal":"I cannot "}}]}""",
                    """{"choices":[{"delta":{$content"refusal":"help."},"finish_reason":"stop"}]}""",
                    "[DONE]",
                ),
            ) { received.append(it) }
            val message = requireNotNull(response.choices.single().message)
            assertEquals("I cannot help.", received.toString())
            assertEquals(received.toString(), message.effectiveContent)
            assertTrue(!message.effectiveContent.isNullOrBlank())
            val bodyText = StringBuilder()
            val body = readChatCompletionBody(
                """{"choices":[{"message":{"role":"assistant",$content"refusal":"I cannot help."},"finish_reason":"stop"}]}""",
            ) { bodyText.append(it) }
            assertEquals(message.effectiveContent, body.choices.single().message!!.effectiveContent)
            assertEquals(received.toString(), bodyText.toString())
        }
    }

    @Test
    fun `visible content wins over refusal and whitespace fragments are preserved`() = runTest {
        val received = StringBuilder()
        val response = readChatCompletionStream(
            events(
                """{"choices":[{"delta":{"content":"Hello","refusal":"not visible"}}]}""",
                """{"choices":[{"delta":{"content":" "}}]}""",
                """{"choices":[{"delta":{"content":"world"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        ) { received.append(it) }
        assertEquals("Hello world", received.toString())
        assertEquals(received.toString(), response.choices.single().message!!.effectiveContent)
    }

    @Test
    fun `partial text survives a disconnect and unfinished tool arguments never return for execution`() = runTest {
        val received = StringBuilder()
        assertFailsWith<StreamInterruptedException> {
            readChatCompletionStream(
                events(
                    """{"choices":[{"delta":{"content":"Saved prefix"}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"fetch_url","arguments":"{"}}]}}]}""",
                ),
            ) { received.append(it) }
        }
        assertEquals("Saved prefix", received.toString())
        assertFailsWith<StreamInterruptedException> { readChatCompletionStream(events("""{"choices":[{"delta":{},"finish_reason":"length"}]}""", "[DONE]")) {} }
    }

    @Test
    fun `invalid complete arguments and malformed stream events are not executable`() = runTest {
        assertFailsWith<StreamInterruptedException> {
            readChatCompletionStream(events("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"fetch_url","arguments":"{"}}]},"finish_reason":"tool_calls"}]}""", "[DONE]")) {}
        }
        assertFailsWith<StreamInterruptedException> { readChatCompletionStream(events("{invalid")) {} }
    }

    @Test
    fun `Anthropic preserves thinking signatures and opaque continuation fields`() = runTest {
        val result = readAnthropicStream(
            events(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":"","future_required_field":"opaque"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reasoning"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signature"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call","name":"fetch_url","input":{}}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"url\":\"https://example.invalid\"}"}}""",
                """{"type":"content_block_stop","index":1}""",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                """{"type":"message_stop"}""",
            ),
        ) {}
        assertEquals("signature", result.content.first().signature)
        assertEquals("reasoning", result.content.first().thinking)
        assertTrue(result.rawContent.toString().contains("future_required_field"))
        assertEquals("fetch_url", result.content.last().name)
    }

    @Test
    fun `Anthropic interrupted argument block cannot escape at message stop`() = runTest {
        assertFailsWith<StreamInterruptedException> {
            readAnthropicStream(
                events(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call","name":"fetch_url","input":{}}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                    """{"type":"message_stop"}""",
                ),
            ) {}
        }
    }

    @Test
    fun `Gemini emits non-thinking text and preserves signed function calls`() = runTest {
        val text = StringBuilder()
        val result = readGeminiStream(
            events(
                """{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Visible"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_email","args":{}},"thoughtSignature":"opaque"}]},"finishReason":"STOP"}]}""",
            ),
        ) { text.append(it) }
        assertEquals("Visible", text.toString())
        assertEquals("opaque", result.candidates.single().content!!.parts!!.last().thoughtSignature)
        assertFailsWith<StreamInterruptedException> { readGeminiStream(events("""{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"MAX_TOKENS"}]}""")) {} }
    }

    @Test
    fun `Gemini transport fragments form one logical part and match final and regular JSON extraction`() = runTest {
        val received = mutableListOf<String>()
        val result = readGeminiStream(
            events(
                """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"lo"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"\n\n"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"https://example.invalid"}]},"finishReason":"STOP"}],"usageMetadata":{"candidatesTokenCount":5}}""",
            ),
        ) { received.add(it) }
        assertEquals(listOf("Hel", "lo", "\n\n", "https://example.invalid"), received)
        assertEquals("Hello\n\nhttps://example.invalid", result.extractText())
        assertEquals(received.joinToString(""), result.extractText())
        assertEquals(1, result.candidates.single().content!!.parts!!.size)
        val bodyText = StringBuilder()
        val body = readGeminiBody(
            """{"candidates":[{"content":{"parts":[{"text":"Hello\n\nhttps://example.invalid"}]},"finishReason":"STOP"}],"usageMetadata":{"candidatesTokenCount":5}}""",
        ) { bodyText.append(it) }
        assertEquals(body, result)
        assertEquals(body.extractText(), bodyText.toString())

        val history = MutableStateFlow<List<History>>(emptyList())
        val assistant = StreamedAssistant(history, this)
        received.forEach { assistant.append(it) }
        assistant.complete(History(role = History.Role.ASSISTANT, content = result.extractText()))
        val settings = AppSettings(MapSettings())
        val conversation = Conversation(id = "gemini-stream", messages = history.value.map { it.toStoredMessage() }, createdAt = 0, updatedAt = 0)
        SettingsConversationPersistence(settings).save(conversation, listOf(conversation))
        val saved = SettingsConversationPersistence(settings).loadAll().single().messages.single()
        assertEquals(received.joinToString(""), saved.content)
    }

    @Test
    fun `Gemini thinking signatures and function calls remain boundaries between logical text parts`() = runTest {
        val received = StringBuilder()
        val result = readGeminiStream(
            events(
                """{"candidates":[{"content":{"parts":[{"text":"think ","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"more","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"thoughtSignature":"thinking-signature"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"lo"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Signed","thoughtSignature":"text-signature"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_email","args":{"id":"synthetic"}},"thoughtSignature":"call-signature"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Wor"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"ld"},{"text":"!"}]},"finishReason":"STOP"}]}""",
            ),
        ) { received.append(it) }
        val parts = result.candidates.single().content!!.parts!!
        assertEquals(7, parts.size)
        assertEquals("think more", parts[0].text)
        assertTrue(parts[0].isThought)
        assertEquals("thinking-signature", parts[1].thoughtSignature)
        assertEquals("Hello", parts[2].text)
        assertEquals("text-signature", parts[3].thoughtSignature)
        assertEquals("read_email", parts[4].functionCall!!.name)
        assertEquals("call-signature", parts[4].thoughtSignature)
        assertEquals("World", parts[5].text)
        assertEquals("!", parts[6].text)
        assertEquals("Hello\nSigned\nWorld\n!", received.toString())
        assertEquals(received.toString(), result.extractText())
        // This is also the tool-loop repository's completed-text projection.
        assertEquals(received.toString(), parts.filterNot { it.isThought }.mapNotNull { it.text }.joinToString("\n"))
        val bodyText = StringBuilder()
        val body = readGeminiBody(streamJson.encodeToString(GeminiChatResponseDto.serializer(), result)) { bodyText.append(it) }
        assertEquals(result, body)
        assertEquals(received.toString(), body.extractText())
        assertEquals(received.toString(), bodyText.toString())
    }

    @Test
    fun `Gemini signed and thinking text cannot merge into subsequent unsigned text`() = runTest {
        val received = StringBuilder()
        val result = readGeminiStream(
            events(
                """{"candidates":[{"content":{"parts":[{"text":"Signed","thoughtSignature":"opaque"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Visible"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Again"}]},"finishReason":"STOP"}]}""",
            ),
        ) { received.append(it) }
        assertEquals(4, result.candidates.single().content!!.parts!!.size)
        assertEquals("Signed\nVisible\nAgain", received.toString())
        assertEquals(received.toString(), result.extractText())
    }

    @Test
    fun `Gemini merged fragments still count toward the stream safety limit`() = runTest {
        val parts = List(32_768) { """{"text":""}""" }.joinToString(",")
        val failure = assertFailsWith<StreamInterruptedException> {
            readGeminiStream(
                events(
                    """{"candidates":[{"content":{"parts":[$parts]}}]}""",
                    """{"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}]}""",
                ),
            ) {}
        }
        assertTrue(failure.message.orEmpty().contains("too many fragments"))
    }

    @Test
    fun `Gemini missing terminal status preserves text without returning a complete response`() = runTest {
        val received = StringBuilder()
        assertFailsWith<StreamInterruptedException> {
            readGeminiStream(events("""{"candidates":[{"content":{"parts":[{"text":"prefix"}]}}]}""")) { received.append(it) }
        }
        assertEquals("prefix", received.toString())
        val cancelled = CancellationException("Stop")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                readGeminiStream(events("""{"candidates":[{"content":{"parts":[{"text":"prefix"}]}}]}""")) { throw cancelled }
            },
        )
        val write = ConversationWriteException(IllegalStateException("quota"))
        assertSame(
            write,
            assertFailsWith<ConversationWriteException> {
                readGeminiStream(events("""{"candidates":[{"content":{"parts":[{"text":"prefix"}]}}]}""")) { throw write }
            },
        )
    }

    @Test
    fun `ordinary JSON fallback preserves partial text but rejects incomplete finishes for every API`() = runTest {
        val received = mutableListOf<String>()
        assertFailsWith<StreamInterruptedException> {
            readChatCompletionBody("""{"choices":[{"message":{"role":"assistant","content":"chat"},"finish_reason":"length"}]}""") { received.add(it) }
        }
        assertFailsWith<StreamInterruptedException> {
            readAnthropicBody("""{"content":[{"type":"text","text":"anthropic"}],"stop_reason":"max_tokens"}""") { received.add(it) }
        }
        assertFailsWith<StreamInterruptedException> {
            readGeminiBody("""{"candidates":[{"content":{"parts":[{"text":"gemini"}]},"finishReason":"MAX_TOKENS"}]}""") { received.add(it) }
        }
        assertFailsWith<StreamInterruptedException> {
            readResponsesBody("""{"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"responses"}]}]}""") { received.add(it) }
        }
        assertEquals(listOf("chat", "anthropic", "gemini", "responses"), received)
    }

    @Test
    fun `cancellation and persistence failures keep their identity instead of becoming retryable errors`() = runTest {
        val chunk = """{"choices":[{"delta":{"content":"text"}}]}"""
        val cancelled = CancellationException("Stop")
        assertSame(cancelled, assertFailsWith<CancellationException> { readChatCompletionStream(events(chunk)) { throw cancelled } })
        val write = ConversationWriteException(IllegalStateException("quota"))
        assertSame(write, assertFailsWith<ConversationWriteException> { readChatCompletionStream(events(chunk)) { throw write } })
    }
}
