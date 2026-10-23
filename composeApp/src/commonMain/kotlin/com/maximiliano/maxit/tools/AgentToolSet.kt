// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.tools

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.EmailStore
import com.maximiliano.maxit.data.MemoryStore
import com.maximiliano.maxit.data.TaskStore
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.tools.Tool
import com.maximiliano.maxit.skills.SkillManager

/**
 * The tool set every platform builds, in the order the agent receives it.
 *
 * Each platform's `getAvailableTools()` used to repeat this gating block verbatim, which is how
 * they drifted — Android in particular had re-implemented the common-tool checks inline instead
 * of calling [CommonTools.getCommonTools]. Platform-specific tools are contributed through
 * [platformExtras]; MCP tools stay last everywhere.
 */
fun buildAgentToolSet(
    appSettings: AppSettings,
    memoryStore: MemoryStore,
    taskStore: TaskStore,
    mcpServerManager: McpServerManager,
    // Web has no email support and injects no store.
    emailStore: EmailStore? = null,
    // Only platforms with app-owned skill storage contribute skill tools.
    skillManager: SkillManager? = null,
    platformExtras: MutableList<Tool>.() -> Unit = {},
): List<Tool> = buildList {
    addAll(CommonTools.getCommonTools(appSettings))

    if (appSettings.isMemoryEnabled()) {
        addAll(CommonTools.getMemoryTools(memoryStore))
    }

    if (appSettings.isSchedulingEnabled()) {
        addAll(SchedulingTools.getSchedulingTools(taskStore))
    }

    if (emailStore != null && appSettings.isEmailEnabled()) {
        addAll(EmailTools.getEmailTools(emailStore))
    }

    platformExtras()

    if (skillManager != null) {
        add(SkillTools.create(skillManager))
        add(SkillTools.createSkill(skillManager))
    }

    addAll(mcpServerManager.getEnabledMcpTools(reservedNames = mapTo(HashSet()) { it.schema.name }))
}
