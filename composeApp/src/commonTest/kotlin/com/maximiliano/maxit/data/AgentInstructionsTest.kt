package com.maximiliano.maxit.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import maxit.composeapp.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInstructionsTest {
    @Test
    fun `default instructions are loaded from the bundled markdown`() = runTest {
        val expected = Res.readBytes("files/prompts/agent-instructions.md").decodeToString().trim()
        val actual = loadAgentInstructions()

        assertEquals(expected, actual)
        assertTrue(actual.isNotBlank())
        assertTrue(actual.length <= 4_000, "The default must fit the existing instructions editor")
        assertTrue("## Work" in actual)
        assertTrue("## Talk" in actual)
        assertTrue("## Judgment" in actual)
        assertFalse("soul" in actual.lowercase())
        assertFalse("kai-ui" in actual)
        assertFalse("promote_learning" in actual)
    }

    @Test
    fun `loading custom instructions preserves the legacy setting verbatim`() = runTest {
        val settings = AppSettings(MapSettings())
        val custom = "  Keep my custom instructions.\n"
        settings.setSoulText(custom)

        assertEquals(custom, loadAgentInstructions(settings.getSoulText()))
        assertEquals(custom, settings.getSoulText())
        assertEquals(" \n", loadAgentInstructions(" \n"))
    }

    @Test
    fun `empty saved setting resolves the default without persisting or migrating it`() = runTest {
        val settings = AppSettings(MapSettings())

        assertEquals(loadAgentInstructions(), loadAgentInstructions(settings.getSoulText()))
        assertEquals("", settings.getSoulText())
    }
}
