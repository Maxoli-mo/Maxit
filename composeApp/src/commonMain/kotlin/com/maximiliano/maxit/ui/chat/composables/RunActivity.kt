package com.maximiliano.maxit.ui.chat.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.data.MessageState
import com.maximiliano.maxit.data.ToolApprovalRequest
import com.maximiliano.maxit.ui.chat.History
import com.maximiliano.maxit.ui.kaiAdaptiveCardBorder
import com.maximiliano.maxit.ui.kaiAdaptiveCardColors
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.run_activity
import maxit.composeapp.generated.resources.run_approval_description
import maxit.composeapp.generated.resources.run_approval_title
import maxit.composeapp.generated.resources.run_approve_once
import maxit.composeapp.generated.resources.run_arguments
import maxit.composeapp.generated.resources.run_continue
import maxit.composeapp.generated.resources.run_decline
import maxit.composeapp.generated.resources.run_failed
import maxit.composeapp.generated.resources.run_interrupted
import maxit.composeapp.generated.resources.run_paused
import maxit.composeapp.generated.resources.run_result
import maxit.composeapp.generated.resources.run_unknown_outcome
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ToolApprovalCard(request: ToolApprovalRequest, resolve: (String, Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), colors = kaiAdaptiveCardColors(), border = kaiAdaptiveCardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.run_approval_title, request.name), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(Res.string.run_approval_description), style = MaterialTheme.typography.bodyMedium)
            if (request.uncertain) Text(stringResource(Res.string.run_unknown_outcome), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            SelectionContainer {
                Text(request.arguments, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { resolve(request.id, false) }) { Text(stringResource(Res.string.run_decline)) }
                TextButton(onClick = { resolve(request.id, true) }) { Text(stringResource(Res.string.run_approve_once)) }
            }
        }
    }
}

@Composable
internal fun RunActivity(marker: History, history: List<History>, onContinue: (() -> Unit)?) {
    val entries = remember(history, marker.runId) { history.filter { it.runId == marker.runId && it.role == History.Role.TOOL } }
    var expanded by remember(marker.id) { mutableStateOf(false) }
    val title = when (marker.state) {
        MessageState.INTERRUPTED -> Res.string.run_interrupted
        MessageState.PAUSED -> Res.string.run_paused
        MessageState.FAILED -> Res.string.run_failed
        else -> null
    }
    if (title == null && entries.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            if (marker.content.isNotBlank()) Text(marker.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            if (entries.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) { Text(stringResource(Res.string.run_activity)) }
            if (onContinue != null) TextButton(onClick = onContinue) { Text(stringResource(Res.string.run_continue)) }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (entry in entries) {
                    val call = history.asSequence().flatMap { it.toolCalls.orEmpty().asSequence() }.firstOrNull { it.id == entry.toolCallId }
                    Text(entry.toolName.orEmpty(), style = MaterialTheme.typography.labelMedium)
                    if (entry.content.contains("\"outcome_unknown\"")) Text(stringResource(Res.string.run_unknown_outcome), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (call != null) {
                                Text(stringResource(Res.string.run_arguments), style = MaterialTheme.typography.labelSmall)
                                Text(call.arguments, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            Text(stringResource(Res.string.run_result), style = MaterialTheme.typography.labelSmall)
                            Text(entry.content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
