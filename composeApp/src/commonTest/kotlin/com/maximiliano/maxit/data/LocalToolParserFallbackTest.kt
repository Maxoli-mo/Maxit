package com.maximiliano.maxit.data

import com.maximiliano.maxit.inference.DownloadError
import com.maximiliano.maxit.inference.DownloadedModel
import com.maximiliano.maxit.inference.EngineState
import com.maximiliano.maxit.inference.InferenceMessage
import com.maximiliano.maxit.inference.LocalInferenceEngine
import com.maximiliano.maxit.inference.LocalModel
import com.maximiliano.maxit.inference.LocalTool
import com.maximiliano.maxit.inference.LocalToolResult
import com.maximiliano.maxit.inference.ModelImportError
import com.maximiliano.maxit.inference.ModelImportResult
import com.maximiliano.maxit.skills.SkillManifest
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocalToolParserFallbackTest {
    private val skill = SkillManifest(
        id = "review",
        displayName = "Review",
        description = "Review code carefully.",
        body = "Preserve the caller's scope. Report concrete bugs only.",
    )
    private val tools = listOf(LocalTool("load_skill", "{}") { error("Must not execute tools") })
    private val messages = listOf(InferenceMessage("user", "Review this change."))
    private val runtime = ChatPromptRuntimeContext(
        nowLocalIsoWithOffset = "2026-10-23T12:00:00Z",
        timeZoneId = "UTC",
        nowUtcIsoString = "2026-10-23T12:00:00Z",
        platform = "Test",
        modelId = "heartbeat-candidate-model",
        providerName = "On-device candidate",
    )

    private fun prompt(toolsEnabled: Boolean, activeSkill: SkillManifest? = null) = buildChatSystemPrompt(
        variant = SystemPromptVariant.CHAT_LOCAL,
        agentInstructions = "Built-in behavior.",
        customInstructions = "Caller agent instructions.",
        hasTools = toolsEnabled,
        memoryEnabled = false,
        schedulingEnabled = false,
        memoryInstructions = null,
        generalMemories = emptyList(),
        preferenceMemories = emptyList(),
        learningMemories = emptyList(),
        errorMemories = emptyList(),
        pendingTasks = emptyList(),
        heartbeatAdditions = emptyList(),
        emailAccounts = emptyList(),
        runtime = runtime,
        activeSkill = activeSkill,
        installedSkills = if (toolsEnabled) listOf(skill) else emptyList(),
    )

    @Test
    fun `parser retry dispatch removes catalog and tool guidance but preserves selected skill`() = runTest {
        for (activeSkill in listOf(null, skill)) {
            val engine = RecordingEngine(RuntimeException("INVALID_ARGUMENT: malformed function call"))
            val originalPrompt = prompt(toolsEnabled = true, activeSkill = activeSkill)
            var rebuilds = 0

            val answer = engine.chatWithToolParserFallback(messages, originalPrompt, tools) {
                rebuilds++
                prompt(toolsEnabled = false, activeSkill = activeSkill)
            }

            assertEquals("answer", answer)
            assertEquals(1, rebuilds)
            assertEquals(2, engine.calls.size)
            assertEquals(tools.map { it.name to it.descriptionJsonString }, engine.calls[0].tools.map { it.name to it.descriptionJsonString })
            assertEquals(originalPrompt, engine.calls[0].systemPrompt)
            assertTrue("## Installed Skills" in originalPrompt)
            val retry = engine.calls[1]
            assertEquals(messages, retry.messages)
            assertTrue(retry.tools.isEmpty())
            val retryPrompt = requireNotNull(retry.systemPrompt)
            assertFalse("## Installed Skills" in retryPrompt)
            assertFalse("When a request matches a skill's description, call load_skill" in retryPrompt)
            assertFalse("## Tool Use" in retryPrompt)
            assertTrue("Caller agent instructions." in retryPrompt)
            assertTrue("- Model: heartbeat-candidate-model" in retryPrompt)
            assertTrue("- Provider: On-device candidate" in retryPrompt)
            assertEquals(activeSkill != null, "## Active skill: Review" in retryPrompt)
            assertEquals(activeSkill != null, skill.body in retryPrompt)
        }
    }

    @Test
    fun `successful dispatch does not build a retry prompt`() = runTest {
        val engine = RecordingEngine()
        assertEquals(
            "answer",
            engine.chatWithToolParserFallback(messages, "custom system instructions", tools) {
                error("Must not rebuild a successful request")
            },
        )
        assertEquals(1, engine.calls.size)
        assertEquals(tools.map { it.name to it.descriptionJsonString }, engine.calls.single().tools.map { it.name to it.descriptionJsonString })
    }

    @Test
    fun `custom system and silent task intent survive retry unchanged by default`() = runTest {
        for (question in listOf("Run this heartbeat check.", "Summarize these older messages.", "Extract memory facts only.")) {
            val engine = RecordingEngine(IllegalArgumentException("INVALID_ARGUMENT: function call parse failed"))
            val taskMessages = listOf(InferenceMessage("user", question))
            engine.chatWithToolParserFallback(taskMessages, "Custom summarizer instructions, not a chat prompt.", tools)

            assertEquals(2, engine.calls.size)
            assertEquals(engine.calls[0].systemPrompt, engine.calls[1].systemPrompt)
            assertEquals(taskMessages, engine.calls[1].messages)
            assertTrue(engine.calls[1].tools.isEmpty())
        }
    }

    @Test
    fun `cancellation never retries or rebuilds the prompt`() = runTest {
        val cancellation = CancellationException("cancelled")
        val engine = RecordingEngine(cancellation)
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                engine.chatWithToolParserFallback(messages, "custom", tools) { error("Must not rebuild") }
            },
        )
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `non runtime failure is not caught`() = runTest {
        val failure = Exception("unrelated checked failure")
        val engine = RecordingEngine(failure)
        assertSame(
            failure,
            assertFailsWith<Exception> {
                engine.chatWithToolParserFallback(messages, "custom", tools) { error("Must not rebuild") }
            },
        )
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `retry failure propagates without a third dispatch`() = runTest {
        val failure = IllegalStateException("plain chat failed")
        val engine = RecordingEngine(RuntimeException("INVALID_ARGUMENT: parser failed"), failure)
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                engine.chatWithToolParserFallback(messages, "original", tools) { "without tools" }
            },
        )
        assertEquals(2, engine.calls.size)
        assertEquals("without tools", engine.calls[1].systemPrompt)
        assertTrue(engine.calls[1].tools.isEmpty())
    }

    @Test
    fun `failure after tool entry never retries the action`() = runTest {
        val failure = RuntimeException("Native completion failed after the action")
        var attempts = 0
        var executions = 0
        val engine = object : LocalInferenceEngine by RecordingEngine() {
            override suspend fun chat(messages: List<InferenceMessage>, systemPrompt: String?, tools: List<LocalTool>): String {
                attempts++
                assertEquals("saved", tools.single().execute("{}").content)
                throw failure
            }
        }
        assertSame(
            failure,
            assertFailsWith<RuntimeException> {
                engine.chatWithToolParserFallback(
                    messages,
                    "custom",
                    listOf(
                        LocalTool("action", "{}") {
                            executions++
                            LocalToolResult("saved")
                        },
                    ),
                ) { error("Must not rebuild after an action") }
            },
        )
        assertEquals(1, attempts)
        assertEquals(1, executions)
    }

    @Test
    fun `failure after streamed text never retries the response`() = runTest {
        val failure = RuntimeException("Native stream disconnected")
        var attempts = 0
        val received = StringBuilder()
        val engine = object : LocalInferenceEngine by RecordingEngine() {
            override suspend fun chatStreaming(messages: List<InferenceMessage>, systemPrompt: String?, tools: List<LocalTool>, onText: suspend (String) -> Unit): String {
                attempts++
                onText("Saved prefix")
                throw failure
            }
        }
        assertSame(
            failure,
            assertFailsWith<RuntimeException> {
                engine.chatWithToolParserFallback(messages, "custom", tools, { received.append(it) }) { error("Must not rebuild after text") }
            },
        )
        assertEquals(1, attempts)
        assertEquals("Saved prefix", received.toString())
    }

    private data class Call(val messages: List<InferenceMessage>, val systemPrompt: String?, val tools: List<LocalTool>)

    private class RecordingEngine(
        private val firstFailure: Exception? = null,
        private val retryFailure: Exception? = null,
    ) : LocalInferenceEngine {
        val calls = mutableListOf<Call>()

        override suspend fun chat(messages: List<InferenceMessage>, systemPrompt: String?, tools: List<LocalTool>): String {
            calls += Call(messages, systemPrompt, tools)
            if (calls.size == 1) firstFailure?.let { throw it }
            if (calls.size == 2) retryFailure?.let { throw it }
            return "answer"
        }

        override val engineState = MutableStateFlow(EngineState.READY)
        override val downloadingModelId = MutableStateFlow<String?>(null)
        override val downloadProgress = MutableStateFlow<Float?>(null)
        override val downloadError = MutableStateFlow<DownloadError?>(null)
        override val importingFileName = MutableStateFlow<String?>(null)
        override val importProgress = MutableStateFlow<Float?>(null)
        override val importError = MutableStateFlow<ModelImportError?>(null)
        override val currentModelId: String? = null
        override suspend fun initialize(model: DownloadedModel, contextTokens: Int): Unit = error("Not used")
        override suspend fun release(): Unit = error("Not used")
        override fun releaseInBackground(): Unit = error("Not used")
        override fun getDownloadedModels(): List<DownloadedModel> = error("Not used")
        override fun getAvailableModels(): List<LocalModel> = error("Not used")
        override fun getImportedLocalModels(): List<LocalModel> = error("Not used")
        override fun getFreeSpaceBytes(): Long = error("Not used")
        override fun startDownload(model: LocalModel): Unit = error("Not used")
        override fun cancelDownload(): Unit = error("Not used")
        override suspend fun deleteModel(modelId: String): Unit = error("Not used")
        override suspend fun importModel(source: PlatformFile): ModelImportResult = error("Not used")
        override fun cancelImport(): Unit = error("Not used")
    }
}
