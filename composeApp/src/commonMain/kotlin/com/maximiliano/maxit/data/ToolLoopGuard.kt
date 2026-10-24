package com.maximiliano.maxit.data

import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.chat.ToolCallInfo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun canonicalToolJson(value: String): String {
    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(element.map { sorted(it) })
        else -> element
    }
    return runCatching { sorted(Json.parseToJsonElement(value)).toString() }.getOrDefault(value)
}

/** Bounded evidence, not a claim to understand arbitrary task progress. */
internal class ToolLoopGuard {
    private data class Round(val calls: List<String>, val results: List<String>, val polling: Boolean, val failed: Boolean)
    private val rounds = ArrayDeque<Round>()
    private var recoveryUsed = false
    private var seeded = false
    private var recoveryAttempted = false

    fun check(calls: List<ToolCallInfo>, history: List<History>): String? {
        if (calls.isEmpty()) return null
        if (!seeded) {
            seeded = true
            val requestStart = history.indexOfLast { it.role == History.Role.USER && !it.superseded }.coerceAtLeast(0)
            val requestHistory = history.drop(requestStart).filterNot { it.superseded }
            val receipts = requestHistory.filter { it.role == History.Role.TOOL }.associateBy { it.toolCallId }
            requestHistory.filter { !it.toolCalls.isNullOrEmpty() }.takeLast(24).forEach { entry ->
                val results = entry.toolCalls.orEmpty().mapNotNull { call -> receipts[call.id]?.let { Triple(call.id, call.name, it.content) } }
                if (results.size == entry.toolCalls.orEmpty().size) record(entry.toolCalls.orEmpty(), results)
            }
        }
        val signature = signatures(calls)
        if (rounds.lastOrNull()?.polling == true && isProcessPoll(calls)) return null
        val recent = rounds.toList()
        if (recent.size >= 6 && recent.takeLast(6).all { it.failed }) {
            if (recoveryUsed && (recoveryAttempted || recent.takeLast(6).any { it.calls == signature })) throw RunPausedException("Paused after repeated tool failures. Review the saved errors before continuing.")
            if (!recoveryUsed) {
                recoveryUsed = true
                return "Six consecutive rounds failed. Make one different, safe diagnostic attempt instead of repeating failed actions."
            }
        }
        for (period in 1..minOf(4, recent.size / 2)) {
            val first = recent.takeLast(period * 2).take(period)
            val second = recent.takeLast(period)
            if (first == second && second.first().calls == signature) {
                val reason = "Repeated tool calls returned the same results in a cycle. Try a different approach; do not repeat the blocked calls."
                if (recoveryUsed) throw RunPausedException("Paused because tool execution repeated without new results. Completed work is saved. Continue only after reviewing the approach.")
                recoveryUsed = true
                return reason
            }
        }
        return null
    }

    suspend fun backoffIfPolling(calls: List<ToolCallInfo>) {
        if (rounds.lastOrNull()?.let { it.polling && isProcessPoll(calls) } == true) delay(2000)
    }

    fun record(calls: List<ToolCallInfo>, results: List<Triple<String, String, String>>) {
        val polling = isProcessPoll(calls) && results.all { (_, _, result) ->
            val json = runCatching { Json.parseToJsonElement(result) as? JsonObject }.getOrNull()
            (json?.get("status") as? JsonPrimitive)?.contentOrNull == "running" || (json?.get("running") as? JsonPrimitive)?.contentOrNull == "true" || (json?.get("running") as? JsonArray)?.isNotEmpty() == true
        }
        val failed = results.isNotEmpty() && results.all { (_, _, result) ->
            val value = runCatching { Json.parseToJsonElement(result) as? JsonObject }.getOrNull()
            (value?.get("success") as? JsonPrimitive)?.contentOrNull == "false" || value?.get("error")?.let { it != kotlinx.serialization.json.JsonNull } == true
        }
        rounds.addLast(Round(signatures(calls), results.map { canonicalToolJson(it.third) }.sorted(), polling, failed))
        if (recoveryUsed) recoveryAttempted = true
        if (rounds.size > 24) rounds.removeFirst()
    }

    private fun isProcessPoll(calls: List<ToolCallInfo>) = calls.isNotEmpty() && calls.all { call ->
        val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
        call.name == "manage_process" && (args?.get("action") as? JsonPrimitive)?.contentOrNull in setOf("list", "log", "status", "output", "read")
    }

    private fun signatures(calls: List<ToolCallInfo>) = calls.map { signature(it) }.sorted()
    private fun signature(call: ToolCallInfo) = "${call.name}:${canonicalToolJson(call.arguments)}"
}
