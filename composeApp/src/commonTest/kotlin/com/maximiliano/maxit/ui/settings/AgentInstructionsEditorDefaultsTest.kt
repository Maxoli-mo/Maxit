package com.maximiliano.maxit.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentInstructionsEditorDefaultsTest {
    @Test
    fun `saved instructions remain selected before and after failed default read`() = runTest {
        val saved = "  Keep my custom instructions.\n"
        val initialEditorText = selectAgentInstructionsEditorText(saved, null)
        val failure = IllegalStateException("Bundled instructions unavailable")
        val defaultInstructions = loadAgentInstructionsEditorDefault { throw failure }

        assertSame(failure, defaultInstructions.exceptionOrNull())
        assertEquals(saved, initialEditorText)
        // The UI remembers the draft by selected text, so failure must not change that key.
        assertEquals(initialEditorText, selectAgentInstructionsEditorText(saved, defaultInstructions))
        assertNull(defaultInstructions.getOrNull())
    }

    @Test
    fun `failure without saved instructions has no editable default and finishes loading`() = runTest {
        val failure = IllegalStateException("Bundled instructions unavailable")
        val defaultInstructions = loadAgentInstructionsEditorDefault { throw failure }

        assertSame(failure, defaultInstructions.exceptionOrNull())
        assertNull(selectAgentInstructionsEditorText("", defaultInstructions))
    }

    @Test
    fun `available default is selected only when there is no saved override`() = runTest {
        val defaultInstructions = loadAgentInstructionsEditorDefault { "Bundled instructions" }

        assertEquals("Bundled instructions", defaultInstructions.getOrNull())
        assertEquals("Bundled instructions", selectAgentInstructionsEditorText("", defaultInstructions))
        assertEquals("Custom", selectAgentInstructionsEditorText("Custom", defaultInstructions))
        assertEquals(" \n", selectAgentInstructionsEditorText(" \n", defaultInstructions))
        assertNull(selectAgentInstructionsEditorText("", null))
    }

    @Test
    fun `default read cancellation is propagated`() = runTest {
        val cancellation = CancellationException("Editor closed")

        val thrown = assertFailsWith<CancellationException> {
            loadAgentInstructionsEditorDefault { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }
}
