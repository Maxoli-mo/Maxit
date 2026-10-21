package com.maximiliano.maxit.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FossProviderPolicyTest {
    @Test
    fun `fresh settings never enable an implicit hosted fallback`() {
        assertFalse(AppSettings(MapSettings()).isFreeFallbackEnabled())
    }

    @Test
    fun `legacy fallback settings cannot reenable the removed proxy`() {
        val settings = AppSettings(MapSettings())
        settings.setFreeFallbackEnabled(true)
        settings.setFreeServicePrimary(true)
        assertFalse(settings.isFreeFallbackEnabled())
    }

    @Test
    fun `legacy free service has no network endpoint`() {
        assertTrue(Service.Free.chatUrl.isEmpty())
        assertTrue(Service.Free.modelsUrl.isNullOrEmpty())
    }
}
