package com.maximiliano.maxit.data

import com.maximiliano.maxit.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskSchedulerAutomationRegressionTest {
    private class Fixture(scope: TestScope, heartbeatEnabled: Boolean = false) {
        val settings = AppSettings(MapSettings()).apply { setSchedulingEnabled(true) }
        val tasks = TaskStore(settings)
        val heartbeat = HeartbeatManager(settings, tasks).apply {
            saveConfig(HeartbeatConfig(enabled = heartbeatEnabled, intervalMinutes = 0, activeHoursStart = 0, activeHoursEnd = 24))
        }
        val fake = FakeDataRepository()
        val prompts = mutableListOf<String>()
        var execute: suspend (String) -> String = { "HEARTBEAT_OK" }
        val owner = Job(scope.backgroundScope.coroutineContext[Job])
        val repository = object : DataRepository by fake {
            override suspend fun askWithTools(prompt: String, instanceId: String?, conversationIdOverride: String?): String {
                prompts += prompt
                return execute(prompt)
            }
        }
        val scheduler = TaskScheduler(
            repository,
            taskStore = tasks,
            appSettings = settings,
            heartbeatManager = heartbeat,
            backgroundDispatcher = StandardTestDispatcher(scope.testScheduler) + owner,
        )
    }

    private fun TestScope.tick() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun `isolated conversation cancellation pauses only its task and later ticks still run`() = runTest {
        val f = Fixture(this)
        val first = f.tasks.addTask("First", "deleted conversation", 1)
        val second = f.tasks.addTask("Second", "unrelated task", 1)
        val gate = CompletableDeferred<Unit>()
        f.execute = { prompt ->
            if (prompt == first.prompt) {
                gate.await()
                throw CancellationException("Conversation was deleted")
            }
            "HEARTBEAT_OK"
        }
        f.scheduler.start()
        runCurrent()
        tick()
        assertEquals(TaskStatus.RUNNING, f.tasks.getAllTasks().first { it.id == first.id }.status)
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(first.prompt, second.prompt), f.prompts)
        val paused = f.tasks.getAllTasks().first { it.id == first.id }
        assertEquals(TaskStatus.PAUSED, paused.status)
        assertFalse(paused.recentExecutions.single().success)
        assertEquals(TaskStatus.COMPLETED, f.tasks.getAllTasks().first { it.id == second.id }.status)
        assertNotNull(f.scheduler.executionError.value)
        val third = f.tasks.addTask("Third", "next tick", 1)
        tick()
        assertEquals(third.prompt, f.prompts.last())
        assertEquals(3, f.prompts.size)
        f.owner.cancel()
    }

    @Test
    fun `isolated heartbeat cancellation records failure and leaves the loop alive`() = runTest {
        val f = Fixture(this, heartbeatEnabled = true)
        var fail = true
        f.execute = {
            if (fail) throw CancellationException("Conversation was deleted")
            "HEARTBEAT_OK"
        }
        f.scheduler.start()
        runCurrent()
        tick()
        assertFalse(f.heartbeat.getHeartbeatLog().single().success)
        assertNotNull(f.scheduler.executionError.value)
        fail = false
        val next = f.tasks.addTask("Next", "unrelated task", 1)
        tick()
        assertEquals(TaskStatus.COMPLETED, f.tasks.getAllTasks().single().status)
        assertEquals(next.prompt, f.prompts[1])
        assertTrue(f.heartbeat.getHeartbeatLog().first().success)
        assertEquals(3, f.prompts.size)
        f.owner.cancel()
    }

    @Test
    fun `true scheduler cancellation settles its active claim and stops other tasks and ticks`() = runTest {
        val f = Fixture(this)
        val first = f.tasks.addTask("First", "blocked", 1)
        val second = f.tasks.addTask("Second", "must not run", 1)
        f.execute = { CompletableDeferred<String>().await() }
        f.scheduler.start()
        runCurrent()
        tick()
        assertEquals(listOf(first.prompt), f.prompts)
        f.owner.cancel()
        runCurrent()

        assertEquals(TaskStatus.PAUSED, f.tasks.getAllTasks().first { it.id == first.id }.status)
        assertEquals(TaskStatus.PENDING, f.tasks.getAllTasks().first { it.id == second.id }.status)
        tick()
        assertEquals(listOf(first.prompt), f.prompts)
    }

    @Test
    fun `true cancellation during heartbeat propagates without continuing the loop`() = runTest {
        val f = Fixture(this, heartbeatEnabled = true)
        f.execute = { CompletableDeferred<String>().await() }
        f.scheduler.start()
        runCurrent()
        tick()
        assertEquals(1, f.prompts.size)
        f.owner.cancel()
        runCurrent()
        f.tasks.addTask("Next", "must not run", 1)
        tick()
        assertEquals(1, f.prompts.size)
        assertTrue(f.heartbeat.getHeartbeatLog().isEmpty())
    }

    @Test
    fun `repeated heartbeat cycles do not embed saved prompts or status messages`() = runTest {
        val f = Fixture(this, heartbeatEnabled = true)
        f.execute = { prompt ->
            val previous = f.fake.savedConversations.value.singleOrNull()?.messages.orEmpty()
            val index = previous.size
            f.fake.savedConversations.value = listOf(
                Conversation(
                    id = "fake-heartbeat-conversation",
                    messages = previous + listOf(
                        Conversation.Message("user-$index", "user", prompt),
                        Conversation.Message("status-$index", "assistant", "Complete", isStatusMessage = true),
                        Conversation.Message("result-$index", "assistant", "HEARTBEAT_OK"),
                    ),
                    createdAt = 0,
                    updatedAt = 0,
                    type = Conversation.TYPE_HEARTBEAT,
                ),
            )
            "HEARTBEAT_OK"
        }
        f.scheduler.start()
        runCurrent()
        repeat(6) { tick() }
        assertEquals(6, f.prompts.size)
        assertEquals(List(6) { HeartbeatManager.DEFAULT_HEARTBEAT_PROMPT + "\n" }, f.prompts)
        assertEquals(18, f.fake.savedConversations.value.single().messages.size)
        f.owner.cancel()
    }
}
