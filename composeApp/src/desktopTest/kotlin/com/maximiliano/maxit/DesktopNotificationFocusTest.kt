package com.maximiliano.maxit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNotificationFocusTest {
    @Test
    fun `linux notifications use the app name and normal urgency without markup or option injection`() {
        assertEquals(
            listOf("notify-send", "--app-name=Maxit", "--urgency=normal", "--print-id", "--icon", "/tmp/icon.png", "--", "-title", "&lt;b&gt;plain &amp; safe&lt;/b&gt;"),
            linuxNotificationCommand("-title", "<b>plain & safe</b>", "/tmp/icon.png"),
        )
        assertEquals(
            listOf("notify-send", "--app-name=Maxit", "--urgency=normal", "--print-id", "--", "title", "preview"),
            linuxNotificationCommand("title", "preview", null),
        )
    }

    @Test fun `notify only when the app window has lost focus`() {
        val previous = desktopWindowFocused
        try {
            desktopWindowFocused = true
            assertFalse(shouldSendLlmNotification())
            desktopWindowFocused = false
            assertTrue(shouldSendLlmNotification())
        } finally {
            desktopWindowFocused = previous
        }
    }
}
