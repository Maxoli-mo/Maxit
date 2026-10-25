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
    fun `built-in behavior always loads from bundled markdown without changing preferences`() = runTest {
        val settings = AppSettings(MapSettings())
        val custom = "  Keep my custom instructions.\n"
        settings.setSoulText(custom)
        val actual = loadAgentInstructions()

        assertEquals(Res.readBytes("files/prompts/agent-instructions.md").decodeToString().trim(), actual)
        assertTrue(actual.length <= 2_000, "Keep the shared behavioral core small")
        assertTrue("## Responding" in actual)
        assertTrue("## Working" in actual)
        assertFalse("kai-ui" in actual)
        assertFalse("promote_learning" in actual)
        assertEquals(custom, settings.getSoulText())
    }

    @Test
    fun `custom instructions are empty by default and independent of profile`() {
        val settings = AppSettings(MapSettings())
        assertEquals("", settings.getSoulText())
        settings.setUserProfile(UserProfile("Taylor Example", "Sam"))
        assertEquals("", settings.getSoulText())
        settings.setSoulText("Be brief.")
        assertEquals(UserProfile("Taylor Example", "Sam"), settings.getUserProfile())
    }
}
