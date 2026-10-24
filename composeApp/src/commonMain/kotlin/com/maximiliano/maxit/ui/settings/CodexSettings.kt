package com.maximiliano.maxit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.ui.KaiClearableTextField
import com.maximiliano.maxit.ui.handCursor
import com.maximiliano.maxit.ui.rememberCopyToClipboard
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.codex_awaiting_approval
import maxit.composeapp.generated.resources.codex_browser_failed
import maxit.composeapp.generated.resources.codex_cancel
import maxit.composeapp.generated.resources.codex_code_copied
import maxit.composeapp.generated.resources.codex_code_label
import maxit.composeapp.generated.resources.codex_copy_code
import maxit.composeapp.generated.resources.codex_custom_model_hint
import maxit.composeapp.generated.resources.codex_description
import maxit.composeapp.generated.resources.codex_expired
import maxit.composeapp.generated.resources.codex_open_browser
import maxit.composeapp.generated.resources.codex_request_failed
import maxit.composeapp.generated.resources.codex_requesting
import maxit.composeapp.generated.resources.codex_sign_in
import maxit.composeapp.generated.resources.codex_sign_in_failed
import maxit.composeapp.generated.resources.codex_sign_out
import maxit.composeapp.generated.resources.codex_unsupported
import maxit.composeapp.generated.resources.codex_waiting
import maxit.composeapp.generated.resources.settings_custom_model_label
import maxit.composeapp.generated.resources.settings_model_label
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CodexSettings(
    entry: ConfiguredServiceEntry,
    onBeginSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleUseCustomModel: (Boolean) -> Unit,
    onChangeCustomModelId: (String) -> Unit,
) {
    val auth = entry.codexAuth
    val uriHandler = LocalUriHandler.current
    val copyToClipboard = rememberCopyToClipboard()
    var browserFailed by remember(auth.verificationUri) { mutableStateOf(false) }
    var codeCopied by remember(auth.userCode) { mutableStateOf(false) }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (auth.status == CodexAuthStatus.Unsupported) {
                Text(stringResource(Res.string.codex_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (auth.status == CodexAuthStatus.SignedOut) {
                Text(
                    stringResource(Res.string.codex_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (auth.status) {
                CodexAuthStatus.SignedOut -> {
                    Button(onClick = onBeginSignIn, modifier = Modifier.handCursor().heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.codex_sign_in))
                    }
                }

                CodexAuthStatus.RequestingCode -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(Res.string.codex_requesting), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onCancelSignIn, modifier = Modifier.handCursor().heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.codex_cancel))
                        }
                    }
                }

                CodexAuthStatus.Waiting -> {
                    Text(stringResource(Res.string.codex_waiting), style = MaterialTheme.typography.bodyMedium)
                    auth.userCode?.let { code ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        stringResource(Res.string.codex_code_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    SelectionContainer {
                                        Text(code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        copyToClipboard(code)
                                        codeCopied = true
                                    },
                                    modifier = Modifier.handCursor().size(48.dp),
                                ) {
                                    Icon(
                                        imageVector = if (codeCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(if (codeCopied) Res.string.codex_code_copied else Res.string.codex_copy_code),
                                    )
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        auth.verificationUri?.let { uri ->
                            Button(
                                onClick = { browserFailed = runCatching { uriHandler.openUri(uri) }.isFailure },
                                modifier = Modifier.handCursor().heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(Res.string.codex_open_browser))
                            }
                        }
                        TextButton(onClick = onCancelSignIn, modifier = Modifier.handCursor().heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.codex_cancel))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(Res.string.codex_awaiting_approval),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CodexAuthStatus.SignedIn -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            ConnectionStatusIndicator(entry.connectionStatus)
                        }
                        TextButton(onClick = onSignOut, modifier = Modifier.handCursor()) {
                            Text(stringResource(Res.string.codex_sign_out))
                        }
                    }
                    ModelSelection(entry.selectedModel, entry.models, onSelectModel)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = entry.useCustomModel, role = Role.Checkbox, onValueChange = onToggleUseCustomModel)
                            .handCursor(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = entry.useCustomModel, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_custom_model_label), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (entry.useCustomModel) {
                        KaiClearableTextField(
                            value = entry.customModelId,
                            onValueChange = onChangeCustomModelId,
                            label = { Text(stringResource(Res.string.settings_model_label)) },
                            singleLine = true,
                        )
                        Text(
                            stringResource(Res.string.codex_custom_model_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CodexAuthStatus.Unsupported -> Unit
            }
            auth.error?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            CodexAuthError.RequestFailed -> Res.string.codex_request_failed
                            CodexAuthError.SignInFailed -> Res.string.codex_sign_in_failed
                            CodexAuthError.Expired -> Res.string.codex_expired
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (browserFailed) {
                Text(stringResource(Res.string.codex_browser_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                auth.verificationUri?.let { uri ->
                    SelectionContainer {
                        Text(uri, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
