// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui.chat

import com.maximiliano.maxit.data.Attachment
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the Bug A invariant: when the target service can't accept content-parts payloads
 * (the kai9000 proxy fans out to text-only Groq fallbacks on rate-limit), image attachments
 * must be dropped from the outgoing message and `content` must be a plain string.
 *
 * Sending a `JsonArray` content to gpt-oss-20b/120b triggers a 400:
 * "messages[N].content must be a string".
 */
class ToGroqMessageDtoImageTest {

    private fun userWithImage() = History(
        role = History.Role.USER,
        content = "what's in this picture?",
        attachments = persistentListOf(
            Attachment(data = "BASE64IMAGEDATA", mimeType = "image/png", fileName = "cat.png"),
        ),
    )

    @Test
    fun `supportsImages=true emits image_url content-parts array`() {
        val dto = userWithImage().toGroqMessageDto(supportsImages = true)

        val content = dto.content
        assertTrue(content is JsonArray, "expected content-parts array, got ${content?.let { it::class.simpleName }}")
        // text part + image part
        assertEquals(2, content.size)
    }

    @Test
    fun `supportsImages=false flattens to plain JsonPrimitive and drops images`() {
        val dto = userWithImage().toGroqMessageDto(supportsImages = false)

        assertTrue(dto.content is JsonPrimitive)
        assertTrue(dto.content.content.contains("what's in this picture?"))
        assertTrue(dto.content.content.contains("was not provided"))
    }

    @Test
    fun `plain-text user message is always a JsonPrimitive regardless of flag`() {
        val plain = History(role = History.Role.USER, content = "hi")

        assertEquals(JsonPrimitive("hi"), plain.toGroqMessageDto(supportsImages = true).content)
        assertEquals(JsonPrimitive("hi"), plain.toGroqMessageDto(supportsImages = false).content)
    }
}
