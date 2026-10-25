package com.maximiliano.maxit.data

import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserProfileTest {
    private val profile = UserProfile("Taylor Example", "Sam")

    @Test
    fun `profile persists independently and has its own selectable backup section`() {
        val storage = MapSettings()
        val settings = AppSettings(storage)
        assertEquals(UserProfile(), settings.getUserProfile())
        assertFalse(ImportSection.PROFILE in detectExportableSections(settings.exportToJson(emptyList())))
        settings.setSoulText("Keep answers brief.")
        settings.setUserProfile(profile)
        assertEquals(profile, AppSettings(storage).getUserProfile())
        val exported = settings.exportToJson(emptyList(), setOf(ImportSection.PROFILE))
        assertTrue(ImportSection.PROFILE in detectExportableSections(exported))
        assertTrue(ImportSection.PROFILE in detectImportSections(exported))
        assertFalse("soul_text" in exported)
        val target = AppSettings(MapSettings()).apply { setSoulText("Existing preferences.") }
        assertEquals(0, target.importFromJson(exported, emptyList(), setOf(ImportSection.PROFILE), replace = false))
        assertEquals(profile, target.getUserProfile())
        assertEquals("Existing preferences.", target.getSoulText())
        val instructions = settings.exportToJson(emptyList(), setOf(ImportSection.SOUL))
        assertFalse("user_profile" in instructions)
        assertEquals(0, target.importFromJson(instructions, emptyList(), setOf(ImportSection.SOUL), replace = false))
        assertEquals(profile, target.getUserProfile())
        assertEquals("Keep answers brief.", target.getSoulText())
    }

    @Test
    fun `malformed or oversized profile import preserves both saved names`() {
        val target = AppSettings(MapSettings()).apply { setUserProfile(profile) }
        for (invalid in listOf("[]", "null", "{\"fullName\":[]}", "{\"fullName\":null}", "{\"preferredName\":42}", "{\"preferredName\":false}", "{\"preferredName\":\"${"x".repeat(201)}\"}", "{\"preferredName\":\"Sam\\nOther\"}")) {
            val json = JsonObject(mapOf("user_profile" to Json.parseToJsonElement(invalid)))
            assertEquals(1, target.importFromJson(json, emptyList(), setOf(ImportSection.PROFILE), replace = false))
            assertEquals(profile, target.getUserProfile())
        }
        assertFailsWith<IllegalArgumentException> { UserProfile(preferredName = "x".repeat(201)) }
        assertFailsWith<IllegalArgumentException> { UserProfile(fullName = "Two\nLines") }
    }

    @Test
    fun `unreadable profile is preserved privately instead of overwritten`() {
        val storage = MapSettings().apply { putString(AppSettings.KEY_USER_PROFILE, "{broken profile") }
        val settings = AppSettings(storage)
        assertEquals(UserProfile(), settings.getUserProfile())
        assertEquals("{broken profile", storage.getString(AppSettings.KEY_USER_PROFILE, ""))
    }

    @Test
    fun `replace resets an unselected profile while merge leaves it intact`() {
        val target = AppSettings(MapSettings()).apply { setUserProfile(profile) }
        target.importFromJson(JsonObject(emptyMap()), emptyList(), emptySet(), replace = false)
        assertEquals(profile, target.getUserProfile())
        target.importFromJson(JsonObject(emptyMap()), emptyList(), emptySet(), replace = true)
        assertEquals(UserProfile(), target.getUserProfile())
    }
}
