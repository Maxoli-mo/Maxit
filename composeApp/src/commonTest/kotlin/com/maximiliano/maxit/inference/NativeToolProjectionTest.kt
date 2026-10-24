package com.maximiliano.maxit.inference

import com.maximiliano.maxit.data.RunPausedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeToolProjectionTest {
    @Test
    fun `complete durable source is unchanged while outgoing receipt has a retrieval reference`() {
        val source = "{\"error\":\"uncertain outcome\",\"output\":\"" + "evidence ".repeat(6000) + "\"}"
        val saved = LocalToolResult(source, "durable-call-id")
        val projected = projectNativeToolResults(listOf(saved), 4096, 600).single()
        assertEquals(source, saved.content)
        assertEquals("durable-call-id", saved.historyId)
        assertTrue(projected.length < source.length)
        val excerpt = Json.parseToJsonElement(projected).jsonObject
        assertEquals("true", excerpt.getValue("partial").jsonPrimitive.content)
        assertEquals("durable-call-id", excerpt.getValue("message_id").jsonPrimitive.content)
        assertEquals(source.length.toString(), excerpt.getValue("total_chars").jsonPrimitive.content)
        val end = excerpt.getValue("next_char_offset").jsonPrimitive.content.toInt()
        assertEquals(source.take(end), excerpt.getValue("content").jsonPrimitive.content)
        assertTrue("Missing data must not be interpreted as success or failure" in projected)
        assertTrue(600 + nativeReceiptTokenEstimate(projected) + 128 + 1024 <= 4096)
    }

    @Test
    fun `ordinary small receipts and schemas remain useful in a small window`() {
        val schema = "{\"description\":\"" + "ordinary schema text ".repeat(60) + "\"}"
        val fixed = nativeInputTokenEstimate(schema) + nativeInputTokenEstimate("system prompt and current user")
        val content = "{\"success\":true,\"time\":\"12:00\"}"
        assertEquals(listOf(content), projectNativeToolResults(listOf(LocalToolResult(content)), 4096, fixed))
        assertTrue(nativeInputTokenEstimate(schema) < schema.length)
    }

    @Test
    fun `multiple dense Unicode receipts share allowance without splitting surrogate pairs`() {
        val source = "🧪漢字".repeat(8000)
        val results = (1..3).map { LocalToolResult(source, "call-$it") }
        val fixed = 900L
        val projected = projectNativeToolResults(results, 8192, fixed)
        assertTrue(fixed + projected.sumOf(::nativeReceiptTokenEstimate) + 128 + 2048 <= 8192)
        projected.forEachIndexed { index, text ->
            val excerpt = Json.parseToJsonElement(text).jsonObject
            val prefix = excerpt.getValue("content").jsonPrimitive.content
            val next = excerpt.getValue("next_char_offset").jsonPrimitive.content.toInt()
            assertEquals("call-${index + 1}", excerpt.getValue("message_id").jsonPrimitive.content)
            assertEquals(source.substring(0, next), prefix)
            assertFalse(prefix.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(source[next].isLowSurrogate())
        }
        assertEquals(source, results.first().content)
    }

    @Test
    fun `required model arguments and receipt metadata can pause without losing evidence`() {
        val saved = LocalToolResult("complete outcome".repeat(1000), "call-original")
        val hugeModelTurn = "{\"arguments\":\"" + "x".repeat(20_000) + "\"}"
        assertFailsWith<RunPausedException> {
            projectNativeToolResults(listOf(saved), 4096, nativeInputTokenEstimate(hugeModelTurn))
        }
        assertEquals("complete outcome".repeat(1000), saved.content)
        assertFailsWith<RunPausedException> {
            projectNativeToolResults(List(50) { saved }, 4096, 100)
        }
    }

    @Test
    fun `large results without durable identity pause rather than inventing a retrieval reference`() {
        assertFailsWith<RunPausedException> {
            projectNativeToolResults(listOf(LocalToolResult("x".repeat(20_000))), 4096, 200)
        }
    }

    @Test
    fun `historical metadata preserves identity and explicitly omits outcome`() {
        val reference = Json.parseToJsonElement(nativeResultReference("shell", LocalToolResult("uncertain", "call-id"))).jsonObject
        assertEquals("call-id", reference.getValue("message_id").jsonPrimitive.content)
        assertEquals("true", reference.getValue("partial").jsonPrimitive.content)
        assertEquals("0", reference.getValue("next_char_offset").jsonPrimitive.content)
        assertEquals("9", reference.getValue("total_chars").jsonPrimitive.content)
    }
}
