package com.maximiliano.maxit.ui.settings

import androidx.lifecycle.ViewModelStore
import com.maximiliano.maxit.DaemonController
import com.maximiliano.maxit.data.DataRepository
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.skills.SkillManifest
import com.maximiliano.maxit.testutil.FakeDataRepository
import com.maximiliano.maxit.tools.AppPermission
import com.maximiliano.maxit.tools.PermissionController
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSkillImportTest {
    private val dispatcher = StandardTestDispatcher()
    private val fakeRepository = FakeDataRepository()
    private val skill = SkillManifest("notes", "Notes", "Write notes", "Instructions")
    private val file = File("/unused/notes.md")

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: DataRepository) = SettingsViewModel(
        repository,
        object : DaemonController {
            override fun start() {}
            override fun stop() {}
        },
        PermissionController(AppPermission.POST_NOTIFICATIONS),
        TaskScheduler(repository, enabled = false),
        dispatcher,
    )

    @Test
    fun `import forwards file refreshes installed skills and blocks repeat submissions`() = runTest {
        val source = PlatformFile(file)
        val completion = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : DataRepository by fakeRepository {
            override suspend fun importSkill(file: PlatformFile): Result<SkillManifest> {
                calls++
                assertEquals(source, file)
                completion.await()
                fakeRepository.skills = listOf(skill)
                return Result.success(skill)
            }
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onShowAddSkillDialog(true)
        viewModel.actions.onImportSkill(source)
        viewModel.actions.onImportSkill(source)
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.state.value.isInstallingSkill)
        assertTrue(viewModel.state.value.showAddSkillDialog)
        assertEquals(1, calls)

        completion.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(skill), viewModel.state.value.skills)
        assertFalse(viewModel.state.value.isInstallingSkill)
        assertFalse(viewModel.state.value.showAddSkillDialog)
        assertNull(viewModel.state.value.skillInstallError)
    }

    @Test
    fun `import failure keeps sheet open and exposes error without changing installed skills`() = runTest {
        fakeRepository.skills = listOf(skill)
        val repository = object : DataRepository by fakeRepository {
            override suspend fun importSkill(file: PlatformFile): Result<SkillManifest> = Result.failure(IllegalArgumentException("Skill already installed: notes"))
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onShowAddSkillDialog(true)
        viewModel.actions.onImportSkill(PlatformFile(file))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isInstallingSkill)
        assertTrue(viewModel.state.value.showAddSkillDialog)
        assertEquals("Skill already installed: notes", viewModel.state.value.skillInstallError)
        assertEquals(listOf(skill), viewModel.state.value.skills)

        viewModel.actions.onShowAddSkillDialog(false)
        viewModel.actions.onShowAddSkillDialog(true)
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.state.value.skillInstallError)
    }

    @Test
    fun `GitHub installation retains repository folder and refreshes installed skills`() = runTest {
        val repository = object : DataRepository by fakeRepository {
            override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> {
                assertEquals("example", owner)
                assertEquals("skills", repo)
                assertEquals("main", ref)
                assertEquals("notes", path)
                fakeRepository.skills = listOf(skill)
                return Result.success(skill)
            }
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onShowAddSkillDialog(true)
        viewModel.actions.onInstallGitHubSkill("https://github.com/example/skills/tree/main/notes")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(skill), viewModel.state.value.skills)
        assertFalse(viewModel.state.value.showAddSkillDialog)
        assertFalse(viewModel.state.value.isInstallingSkill)
    }

    @Test
    fun `skills loaded after startup refresh settings without reopening it`() = runTest {
        val viewModel = viewModel(fakeRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        dispatcher.scheduler.runCurrent()
        fakeRepository.skills = listOf(skill)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(skill), viewModel.state.value.skills)
    }

    @Test
    fun `remove immediately blocks Add and both import paths until Undo restores the skill`() = runTest {
        fakeRepository.skills = listOf(skill)
        var imports = 0
        var installs = 0
        var removals = 0
        val repository = object : DataRepository by fakeRepository {
            override suspend fun importSkill(file: PlatformFile): Result<SkillManifest> {
                imports++
                return Result.success(skill)
            }
            override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> {
                installs++
                return Result.success(skill)
            }
            override suspend fun uninstallSkill(id: String) {
                removals++
            }
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onUninstallSkill(skill.id)
        viewModel.actions.onShowAddSkillDialog(true)
        viewModel.actions.onImportSkill(PlatformFile(file))
        viewModel.actions.onInstallGitHubSkill("https://github.com/example/skills/tree/main/notes")
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.state.value.isRemovingSkill)
        assertFalse(viewModel.state.value.showAddSkillDialog)
        assertFalse(viewModel.state.value.isInstallingSkill)
        assertEquals(0, imports)
        assertEquals(0, installs)
        dispatcher.scheduler.advanceTimeBy(3_999)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, removals)

        viewModel.actions.onUndoDelete()
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.state.value.pendingDeletion)
        assertFalse(viewModel.state.value.isRemovingSkill)
        assertEquals(listOf(skill), viewModel.state.value.skills)
        viewModel.actions.onShowAddSkillDialog(true)
        viewModel.actions.onImportSkill(PlatformFile(file))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, imports)
        assertEquals(0, removals)
    }

    @Test
    fun `import stays blocked after Undo expires until deletion actually completes`() = runTest {
        fakeRepository.skills = listOf(skill)
        val completion = CompletableDeferred<Unit>()
        var imports = 0
        var removals = 0
        val repository = object : DataRepository by fakeRepository {
            override suspend fun uninstallSkill(id: String) {
                removals++
                completion.await()
                fakeRepository.skills = emptyList()
            }
            override suspend fun importSkill(file: PlatformFile): Result<SkillManifest> {
                imports++
                fakeRepository.skills = listOf(skill)
                return Result.success(skill)
            }
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onUninstallSkill(skill.id)
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(4_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(1, removals)
        assertNull(viewModel.state.value.pendingDeletion)
        assertTrue(viewModel.state.value.isRemovingSkill)
        viewModel.actions.onUndoDelete()
        viewModel.actions.onShowAddSkillDialog(true)
        viewModel.actions.onImportSkill(PlatformFile(file))
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.isRemovingSkill)
        assertFalse(viewModel.state.value.showAddSkillDialog)
        assertEquals(0, imports)

        completion.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertFalse(viewModel.state.value.isRemovingSkill)
        assertTrue(viewModel.state.value.skills.isEmpty())
        viewModel.actions.onShowAddSkillDialog(true)
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.showAddSkillDialog)
        viewModel.actions.onImportSkill(PlatformFile(file))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, imports)
        assertEquals(listOf(skill), viewModel.state.value.skills)
    }

    @Test
    fun `rapid second removal stays blocked until both committed deletions finish`() = runTest {
        val second = skill.copy(id = "second")
        fakeRepository.skills = listOf(skill, second)
        val firstCompletion = CompletableDeferred<Unit>()
        val secondCompletion = CompletableDeferred<Unit>()
        val repository = object : DataRepository by fakeRepository {
            override suspend fun uninstallSkill(id: String) {
                (if (id == skill.id) firstCompletion else secondCompletion).await()
                fakeRepository.skills = fakeRepository.skills.filterNot { it.id == id }
            }
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onUninstallSkill(skill.id)
        viewModel.actions.onUninstallSkill(second.id)
        dispatcher.scheduler.runCurrent()
        assertEquals(PendingDeletion.Skill(second.id), viewModel.state.value.pendingDeletion)
        assertEquals(1, viewModel.state.value.skillDeletionsInFlight)

        dispatcher.scheduler.advanceTimeBy(4_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, viewModel.state.value.skillDeletionsInFlight)
        firstCompletion.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.isRemovingSkill)
        assertEquals(1, viewModel.state.value.skillDeletionsInFlight)
        viewModel.actions.onShowAddSkillDialog(true)
        dispatcher.scheduler.runCurrent()
        assertFalse(viewModel.state.value.showAddSkillDialog)

        secondCompletion.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, viewModel.state.value.skillDeletionsInFlight)
        assertFalse(viewModel.state.value.isRemovingSkill)
    }

    @Test
    fun `Undo of a second removal does not unlock imports while the first is in flight`() = runTest {
        val second = skill.copy(id = "second")
        fakeRepository.skills = listOf(skill, second)
        val completion = CompletableDeferred<Unit>()
        val repository = object : DataRepository by fakeRepository {
            override suspend fun uninstallSkill(id: String) {
                assertEquals(skill.id, id)
                completion.await()
                fakeRepository.skills = listOf(second)
            }
        }
        val viewModel = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.actions.onUninstallSkill(skill.id)
        viewModel.actions.onUninstallSkill(second.id)
        viewModel.actions.onUndoDelete()
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.state.value.pendingDeletion)
        assertTrue(viewModel.state.value.isRemovingSkill)

        completion.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.state.value.isRemovingSkill)
        assertEquals(listOf(second), viewModel.state.value.skills)
    }

    @Test
    fun `leaving Settings commits pending removal without cancelling an earlier committed removal`() = runTest {
        val second = skill.copy(id = "second")
        fakeRepository.skills = listOf(skill, second)
        val completion = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        var imports = 0
        val repository = object : DataRepository by fakeRepository {
            override suspend fun uninstallSkill(id: String) {
                started += id
                completion.await()
                fakeRepository.skills = fakeRepository.skills.filterNot { it.id == id }
                finished += id
            }
            override suspend fun importSkill(file: PlatformFile): Result<SkillManifest> {
                imports++
                fakeRepository.skills = listOf(skill)
                return Result.success(skill)
            }
        }
        val original = viewModel(repository)
        val store = ViewModelStore().apply { put("settings", original) }
        original.actions.onUninstallSkill(skill.id)
        original.actions.onUninstallSkill(second.id)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(skill.id), started)
        store.clear()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(skill.id, second.id), started)

        val reopened = viewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reopened.state.collect {} }
        reopened.actions.onShowAddSkillDialog(true)
        reopened.actions.onImportSkill(PlatformFile(file))
        dispatcher.scheduler.runCurrent()
        assertTrue(reopened.state.value.isRemovingSkill)
        assertFalse(reopened.state.value.showAddSkillDialog)
        assertEquals(0, imports)

        completion.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(skill.id, second.id), finished)
        assertFalse(reopened.state.value.isRemovingSkill)
        reopened.actions.onShowAddSkillDialog(true)
        reopened.actions.onImportSkill(PlatformFile(file))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, imports)
        assertEquals(listOf(skill), reopened.state.value.skills)
    }
}
