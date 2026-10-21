// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.tools

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.data.EmailStore
import com.maximiliano.maxit.data.MemoryStore
import com.maximiliano.maxit.data.TaskStore
import com.maximiliano.maxit.mcp.McpServerManager
import com.maximiliano.maxit.network.tools.Tool

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
    platformExtras: MutableList<Tool>.() -> Unit = {},
): List<Tool> = buildList {
    addAll(CommonTools.getCommonTools(appSettings))

    if (appSettings.isMemoryEnabled()) {
        addAll(CommonTools.getMemoryTools(memoryStore))
    }

    // Heartbeat tools ride the scheduling switch — they are only reachable from a scheduled run.
    if (appSettings.isSchedulingEnabled()) {
        addAll(SchedulingTools.getSchedulingTools(taskStore))
        addAll(HeartbeatTools.getHeartbeatTools(memoryStore, appSettings))
    }

    if (emailStore != null && appSettings.isEmailEnabled()) {
        addAll(EmailTools.getEmailTools(emailStore))
    }

    platformExtras()

    addAll(mcpServerManager.getEnabledMcpTools(reservedNames = mapTo(HashSet()) { it.schema.name }))
}
