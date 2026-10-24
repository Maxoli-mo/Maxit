package com.maximiliano.maxit.data

import com.maximiliano.maxit.mcp.McpTool
import com.maximiliano.maxit.network.tools.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** An approval is for this exact invocation, never a model-generated claim of permission. */
data class ToolApprovalRequest(val id: String, val conversationId: String, val name: String, val arguments: String, val uncertain: Boolean = false)

internal class ToolExecutionPermit(val name: String, val arguments: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolExecutionPermit>
}

internal fun needsToolApproval(tool: Tool, arguments: String): Boolean {
    if (tool is McpTool) return true
    val args = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
    fun text(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    return when (tool.schema.name) {
        "get_local_time", "get_location_from_ip", "web_search", "load_skill", "read_conversation_history",
        "check_email", "read_email", "search_email", "check_sms", "read_sms", "search_sms",
        "check_notifications", "read_notification", "search_notifications", "list_tasks",
        "send_sms", "reply_sms",
        -> false

        "fetch_url" -> text("method")?.uppercase()?.let { it !in setOf("GET", "HEAD") } ?: false

        "manage_process" -> text("action") !in setOf("list", "log", "status", "output", "read")

        else -> true
    }
}

internal fun hasUnknownOutcome(history: List<com.maximiliano.maxit.ui.chat.History>, name: String, arguments: String): Boolean = hasInvocationOutcome(history, name, arguments, "outcome_unknown")

internal fun hasDeniedInvocation(history: List<com.maximiliano.maxit.ui.chat.History>, name: String, arguments: String, runId: String): Boolean = hasInvocationOutcome(history, name, arguments, "denied", runId)

private fun hasInvocationOutcome(history: List<com.maximiliano.maxit.ui.chat.History>, name: String, arguments: String, status: String, runId: String? = null): Boolean {
    val ids = history.filter { entry ->
        entry.role == com.maximiliano.maxit.ui.chat.History.Role.TOOL && (runId == null || entry.runId == runId) && runCatching { (Json.parseToJsonElement(entry.content) as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.contentOrNull } == status }.getOrDefault(false)
    }.mapNotNull { it.toolCallId }.toSet()
    return history.any { entry -> entry.toolCalls.orEmpty().any { it.id in ids && it.name == name && canonicalToolJson(it.arguments) == canonicalToolJson(arguments) } }
}

internal class ToolApprovalController {
    private val lock = ConversationLock()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val state = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    val requests: StateFlow<List<ToolApprovalRequest>> = state

    suspend fun await(request: ToolApprovalRequest): Boolean {
        // Provider call IDs can repeat in another chat; stale clicks must not approve new work.
        val approval = request.copy(id = Uuid.random().toString())
        val response = CompletableDeferred<Boolean>()
        lock.locked {
            pending[approval.id] = response
            state.value = state.value + approval
        }
        return try {
            response.await()
        } finally {
            lock.locked {
                pending.remove(approval.id)
                state.value = state.value.filterNot { it.id == approval.id }
            }
        }
    }

    fun resolve(id: String, approved: Boolean) = lock.locked {
        pending[id]?.complete(approved)
        Unit
    }
}
