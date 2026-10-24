package com.maximiliano.maxit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundServiceSelectionTest {
    private val codex = ServiceInstance("codex", Service.OpenAICodex.id)
    private val openAI = ServiceInstance("openai", Service.OpenAI.id)
    private val local = ServiceInstance("local", Service.LiteRT.id)

    @Test
    fun automaticBackgroundAndSilentCallsSkipSignedOutCodex() {
        val instances = listOf(codex, openAI)
        for (preferRemote in listOf(false, true)) {
            assertEquals(openAI, selectBackgroundServiceInstance(instances, preferRemote = preferRemote) { it != codex })
        }
    }

    @Test
    fun signedInCodexKeepsItsPriority() {
        assertEquals(codex, selectBackgroundServiceInstance(listOf(codex, openAI), preferRemote = true) { true })
    }

    @Test
    fun explicitSignedOutAccountReportsItsOwnErrorInsteadOfSwitchingAccounts() {
        assertEquals(codex, selectBackgroundServiceInstance(listOf(codex, openAI), "codex", preferRemote = true) { it != codex })
    }

    @Test
    fun toolCallsPreferUsableRemoteButSilentCallsKeepPrimaryOrder() {
        val instances = listOf(local, codex, openAI)
        assertEquals(openAI, selectBackgroundServiceInstance(instances, preferRemote = true) { it != codex })
        assertEquals(local, selectBackgroundServiceInstance(instances) { it != codex })
        assertEquals(local, selectBackgroundServiceInstance(listOf(local, codex), preferRemote = true) { it != codex })
    }

    @Test
    fun noUsableProviderYieldsNoAutomaticRequest() {
        assertNull(selectBackgroundServiceInstance(listOf(codex, openAI)) { false })
    }
}
