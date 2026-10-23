// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.tools

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.MemoryStore
import com.maximiliano.maxit.data.TaskStore
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.tools.Tool
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the pairing between a tool's executable schema and the [com.maximiliano.maxit.network.tools.ToolInfo]
 * the UI resolves its display name from.
 *
 * A tool that runs but has no definition falls back to showing its raw id in chat, which is how
 * the notification tools regressed: `notificationToolDefinitions` existed but nothing referenced it.
 */
class ToolDefinitionsTest {

    private val definitionIds = CommonTools.commonToolDefinitions.map { it.id }

    @Test
    fun everyCommonToolHasADefinition() {
        // Tools with no collaborators, gated by per-tool switches.
        val stateless: List<Tool> = listOf(
            CommonTools.localTimeTool,
            CommonTools.ipLocationTool,
            CommonTools.openUrlTool,
            WebSearchTool,
            FetchUrlTool,
        )
        val missing = stateless.map { it.schema.name }.filterNot { it in definitionIds }
        assertTrue(missing.isEmpty(), "Executable tools with no ToolInfo (chat shows the raw id): $missing")
    }

    @Test
    fun everyMemoryToolHasADefinition() {
        val missing = CommonTools.getMemoryTools(MemoryStore(AppSettings(MapSettings())))
            .map { it.schema.name }
            .filterNot { it in definitionIds }
        assertTrue(missing.isEmpty(), "Memory tools with no ToolInfo: $missing")
    }

    @Test
    fun learningPromotionIsAbsentFromEnabledToolsAndDefinitions() {
        val appSettings = AppSettings(MapSettings()).apply {
            setMemoryEnabled(true)
            setSchedulingEnabled(true)
        }
        val toolNames = buildAgentToolSet(
            appSettings = appSettings,
            memoryStore = MemoryStore(appSettings),
            taskStore = TaskStore(appSettings),
            mcpServerManager = McpServerManager(appSettings),
        ).map { it.schema.name }

        assertFalse("promote_learning" in toolNames)
        assertFalse("promote_learning" in definitionIds)
        assertTrue(toolNames.containsAll(listOf("memory_store", "memory_forget", "memory_learn", "memory_reinforce")))
        assertTrue("schedule_task" in toolNames)
    }

    @Test
    fun notificationToolDefinitionsAreRegistered() {
        val missing = NotificationTools.notificationToolDefinitions
            .map { it.id }
            .filterNot { it in definitionIds }
        assertTrue(missing.isEmpty(), "Notification tools missing from commonToolDefinitions: $missing")
    }

    @Test
    fun skillLoaderHasALocalizedNonToggleableDefinition() {
        val definition = CommonTools.commonToolDefinitions.single { it.id == SkillTools.ID }
        assertEquals("Load Skill", definition.name)
        assertTrue(definition.nameRes != null)
        assertTrue(definition.descriptionRes != null)
        assertFalse(definition.userToggleable)
    }

    @Test
    fun skillCreatorHasALocalizedCapabilityGatedDefinition() {
        val definition = CommonTools.commonToolDefinitions.single { it.id == SkillTools.CREATE_ID }
        assertEquals("Create Skill", definition.name)
        assertTrue(definition.nameRes != null)
        assertTrue(definition.descriptionRes != null)
        assertFalse(definition.userToggleable)
    }

    @Test
    fun platformsWithoutASkillManagerDoNotAdvertiseTheLoader() {
        val settings = AppSettings(MapSettings())
        val toolNames = buildAgentToolSet(
            appSettings = settings,
            memoryStore = MemoryStore(settings),
            taskStore = TaskStore(settings),
            mcpServerManager = McpServerManager(settings),
        ).map { it.schema.name }
        assertFalse(SkillTools.ID in toolNames)
        assertFalse(SkillTools.CREATE_ID in toolNames)
    }

    @Test
    fun definitionIdsAreUnique() {
        val duplicates = definitionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet(), duplicates, "Duplicate tool definition ids")
    }

    @Test
    fun masterToggleControlledToolsAreNotUserToggleable() {
        // These are gated by a single switch in Settings → Agent, so the Tools tab must not
        // offer per-tool switches for them — nothing would read the value back.
        val masterToggled = SchedulingTools.schedulingToolDefinitions +
            EmailTools.emailToolDefinitions +
            SmsTools.smsToolDefinitions +
            NotificationTools.notificationToolDefinitions
        val leaked = masterToggled.filter { it.userToggleable }.map { it.id }
        assertTrue(leaked.isEmpty(), "Master-toggle-controlled tools exposed as per-tool switches: $leaked")
    }
}
