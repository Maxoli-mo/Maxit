package com.maximiliano.maxit.data

import com.maximiliano.maxit.ui.chat.History
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal fun History.toStoredMessage() = Conversation.Message(
    id = id,
    role = when (role) {
        History.Role.USER -> "user"
        History.Role.ASSISTANT -> "assistant"
        History.Role.TOOL, History.Role.TOOL_EXECUTING -> "tool"
    },
    content = content,
    attachments = attachments,
    uiSubmission = uiSubmission,
    isThinking = isThinking,
    reasoningContent = reasoningContent,
    toolCalls = toolCalls,
    providerBlocks = providerBlocks,
    providerKey = providerKey,
    providerUsage = providerUsage,
    toolCallId = toolCallId,
    toolName = toolName,
    runId = runId,
    state = state,
    isStatusMessage = isStatusMessage,
    superseded = superseded,
    fallbackServiceName = fallbackServiceName,
)

internal fun Conversation.Message.toHistory() = History(
    id = id,
    role = when (role) {
        "user" -> History.Role.USER
        "tool" -> History.Role.TOOL
        else -> History.Role.ASSISTANT
    },
    content = content,
    attachments = when {
        attachments.isNotEmpty() -> attachments.toImmutableList()
        data != null && mimeType != null -> persistentListOf(Attachment(data, mimeType, fileName))
        else -> persistentListOf()
    },
    uiSubmission = uiSubmission,
    isThinking = isThinking,
    reasoningContent = reasoningContent,
    toolCalls = toolCalls?.toImmutableList(),
    providerBlocks = providerBlocks,
    providerKey = providerKey,
    providerUsage = providerUsage,
    toolCallId = toolCallId,
    toolName = toolName,
    runId = runId,
    state = state,
    isStatusMessage = isStatusMessage,
    superseded = superseded,
    fallbackServiceName = fallbackServiceName,
)

/** Add honest receipts for unresolved calls; interruption does not prove an action was undone. */
internal fun settleInterruptedHistory(history: List<History>, state: MessageState, reason: String): List<History> {
    val answered = history.mapNotNull { it.toolCallId }.toSet()
    return buildList {
        for (entry in history) {
            if (entry.role == History.Role.TOOL_EXECUTING) continue
            add(
                if (entry.state == MessageState.RUNNING || entry.state == MessageState.GENERATING) {
                    entry.copy(state = state, content = if (entry.isStatusMessage) reason else entry.content)
                } else {
                    entry
                },
            )
            for (call in entry.toolCalls.orEmpty()) {
                if (call.id !in answered) {
                    add(
                        History(
                            id = "receipt-${call.id}",
                            role = History.Role.TOOL,
                            content = if (call.dispatched) {
                                """{"success":false,"status":"outcome_unknown","error":"Execution was interrupted. This action may have completed; verify its state before repeating it."}"""
                            } else {
                                """{"success":false,"status":"not_started","error":"The run ended before this call was dispatched."}"""
                            },
                            toolCallId = call.id,
                            toolName = call.name,
                            runId = entry.runId,
                            state = MessageState.INTERRUPTED,
                        ),
                    )
                }
            }
        }
    }
}
