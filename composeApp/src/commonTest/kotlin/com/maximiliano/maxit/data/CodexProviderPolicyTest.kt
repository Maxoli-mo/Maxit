package com.maximiliano.maxit.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexProviderPolicyTest {
    @Test
    fun codexIsSeparateFromApiKeyOpenAIAndAlwaysUsesResponses() {
        assertEquals(Service.OpenAICodex, Service.fromId("openai-codex"))
        assertEquals(Service.OpenAI, Service.fromId("openai"))
        assertFalse(Service.OpenAICodex.requiresApiKey)
        assertTrue(Service.OpenAICodex.chatUrl.isEmpty())
        assertFalse(Service.OpenAICodex.supportsPdf)
        for (model in listOf("gpt-5-codex", "gpt-5.3-codex", "gpt-6.1-sol", "future-model")) {
            assertTrue(requiresResponsesApi(Service.OpenAICodex, model), model)
        }
        assertFalse(requiresResponsesApi(Service.OpenAI, "gpt-5-codex"))
        assertFalse(requiresResponsesApi(Service.OpenRouter, "openai/gpt-6.1-sol"))
    }

    @Test
    fun backupsTransferCodexConfigurationButNeverOAuthSessions() {
        val storage = MapSettings()
        val settings = AppSettings(storage)
        settings.setConfiguredServiceInstances(listOf(ServiceInstance("codex", Service.OpenAICodex.id)))
        settings.setInstanceModelId("codex", "gpt-5-codex")
        storage.putString("instance_codex_codex_auth", "private-session-fixture")

        val backup = settings.exportToJson(emptyList())
        assertTrue(backup.toString().contains("openai-codex"))
        assertTrue(backup.toString().contains("gpt-5-codex"))
        assertFalse(backup.toString().contains("private-session-fixture"))
        assertFalse(backup.toString().contains("codex_auth"))

        assertEquals(0, settings.importFromJson(backup, emptyList(), setOf(ImportSection.SERVICES), replace = false))
        assertFalse(storage.hasKey("instance_codex_codex_auth"))
        assertEquals("gpt-5-codex", settings.getInstanceModelId("codex"))
    }

    @Test
    fun removingAnInstanceAlsoRemovesItsOAuthSession() {
        val storage = MapSettings()
        val settings = AppSettings(storage)
        storage.putString("instance_codex_codex_auth", "private-session-fixture")
        settings.setInstanceApiKey("openai", "api-key-fixture")
        settings.removeInstanceSettings("codex")
        assertFalse(storage.hasKey("instance_codex_codex_auth"))
        assertEquals("api-key-fixture", settings.getInstanceApiKey("openai"))
    }

    @Test
    fun mergingWithoutServicesPreservesLocalOAuthSession() {
        val storage = MapSettings()
        val settings = AppSettings(storage)
        settings.setConfiguredServiceInstances(listOf(ServiceInstance("codex", Service.OpenAICodex.id)))
        storage.putString("instance_codex_codex_auth", "private-session-fixture")
        settings.importFromJson(settings.exportToJson(emptyList()), emptyList(), emptySet(), replace = false)
        assertTrue(storage.hasKey("instance_codex_codex_auth"))
        settings.importFromJson(settings.exportToJson(emptyList()), emptyList(), emptySet(), replace = true)
        assertFalse(storage.hasKey("instance_codex_codex_auth"))
    }
}
