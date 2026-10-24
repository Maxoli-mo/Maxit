package com.maximiliano.maxit.data

import com.maximiliano.maxit.network.ContextWindowExceededException
import com.maximiliano.maxit.ui.chat.History
import kotlinx.collections.immutable.persistentListOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextOnlyConversationContextTest {
    @Test
    fun `unsupported binaries use their actual unavailable notice budget and remain saved`() {
        for (mime in listOf("image/png", "application/pdf")) {
            val attachment = Attachment("AA==", mime, "original-file")
            val original = History(role = History.Role.USER, content = "Continue this request", attachments = persistentListOf(attachment))
            for (window in listOf(4096, 8192)) {
                assertFailsWith<ContextWindowExceededException> { conversationContext(listOf(original), null, window) }
                val projected = conversationContext(listOf(original.forTextOnlyInference()), null, window).single()
                assertEquals(original.id, projected.id)
                assertTrue(projected.content.contains("Continue this request"))
                assertTrue(projected.content.contains("original-file is unavailable to this text-only engine"))
                assertTrue(projected.attachments.isEmpty())
                assertEquals(persistentListOf(attachment), original.attachments)
                assertEquals("Continue this request", original.content)
            }
        }
    }

    @Test
    fun `decoded text attachments are accounted as the text sent to native inference`() {
        val content = "Résumé ☕\nSecond line"
        val original = History(
            role = History.Role.USER,
            content = "Summarize this",
            attachments = persistentListOf(Attachment(Base64.encode(content.encodeToByteArray()), "text/plain", "notes.txt")),
        )
        val projected = original.forTextOnlyInference()
        assertTrue(projected.content.contains(content))
        assertTrue(projected.content.contains("Summarize this"))
        assertTrue(projected.attachments.isEmpty())
        assertEquals(original.id, conversationContext(listOf(projected), "Instructions", 4096).last().id)
        assertEquals(Base64.encode(content.encodeToByteArray()), original.attachments.single().data)
    }
}
