// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.skills.SkillManifest
import com.maximiliano.maxit.ui.KaiOutlinedTextField
import com.maximiliano.maxit.ui.components.VerticalScrollbarForScroll
import com.maximiliano.maxit.ui.handCursor
import com.maximiliano.maxit.ui.kaiAdaptiveCardBorder
import com.maximiliano.maxit.ui.kaiAdaptiveCardColors
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.ImmutableList
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.settings_skills
import maxit.composeapp.generated.resources.settings_skills_add
import maxit.composeapp.generated.resources.settings_skills_add_github
import maxit.composeapp.generated.resources.settings_skills_automatic_description
import maxit.composeapp.generated.resources.settings_skills_builtin
import maxit.composeapp.generated.resources.settings_skills_cancel
import maxit.composeapp.generated.resources.settings_skills_files_unavailable
import maxit.composeapp.generated.resources.settings_skills_github_hint
import maxit.composeapp.generated.resources.settings_skills_github_url
import maxit.composeapp.generated.resources.settings_skills_import
import maxit.composeapp.generated.resources.settings_skills_import_hint
import maxit.composeapp.generated.resources.settings_skills_install
import maxit.composeapp.generated.resources.settings_skills_installing
import maxit.composeapp.generated.resources.settings_skills_instructions_only
import maxit.composeapp.generated.resources.settings_skills_none
import maxit.composeapp.generated.resources.settings_skills_remove
import maxit.composeapp.generated.resources.settings_skills_removing
import maxit.composeapp.generated.resources.settings_skills_safety
import maxit.composeapp.generated.resources.settings_skills_supporting_files
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SkillsSection(
    skills: ImmutableList<SkillManifest>,
    onUninstallSkill: (String) -> Unit,
    showAddDialog: Boolean,
    onShowAddDialog: (Boolean) -> Unit,
    onInstallGitHub: (String) -> Unit,
    onImportSkill: (PlatformFile) -> Unit,
    isInstalling: Boolean,
    isRemoving: Boolean,
    installError: String?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_skills),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.settings_skills_automatic_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        if (skills.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_skills_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (skill in skills) {
                SkillCard(
                    skill = skill,
                    onRemove = { onUninstallSkill(skill.id) },
                    canRemove = !isInstalling,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onShowAddDialog(true) },
            enabled = !isInstalling && !isRemoving,
            modifier = Modifier.handCursor(),
        ) {
            Text(stringResource(Res.string.settings_skills_add))
        }
        if (isRemoving) {
            Text(
                text = stringResource(Res.string.settings_skills_removing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { onShowAddDialog(false) },
            onInstallGitHub = onInstallGitHub,
            onImportSkill = onImportSkill,
            isInstalling = isInstalling,
            isRemoving = isRemoving,
            installError = installError,
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillManifest,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().handCursor(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "/${skill.id}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (skill.isBuiltIn) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.settings_skills_builtin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Built-in skills ship in the app and cannot be uninstalled — hide the remove action for them.
            if (expanded && !skill.isBuiltIn) {
                Spacer(Modifier.height(8.dp))

                when {
                    skill.loadError != null -> Text(
                        text = stringResource(Res.string.settings_skills_files_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    skill.bundledFilePaths.isEmpty() -> Text(
                        text = stringResource(Res.string.settings_skills_instructions_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        Text(
                            text = stringResource(Res.string.settings_skills_supporting_files, skill.bundledFilePaths.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        val paths = remember(skill.bundledFilePaths) { skill.bundledFilePaths.sorted().joinToString("\n") }
                        Text(
                            text = paths,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }

                TextButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.handCursor()) {
                    Text(
                        text = stringResource(Res.string.settings_skills_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onInstallGitHub: (String) -> Unit,
    onImportSkill: (PlatformFile) -> Unit,
    isInstalling: Boolean,
    isRemoving: Boolean,
    installError: String?,
) {
    var url by remember { mutableStateOf("") }
    val installing by rememberUpdatedState(isInstalling)
    val canSubmit by rememberUpdatedState(!isInstalling && !isRemoving)
    val filePickerLauncher = if (!LocalInspectionMode.current) {
        rememberFilePickerLauncher(
            type = FileKitType.File(extensions = listOf("md", "zip")),
        ) { file ->
            if (file != null && canSubmit) onImportSkill(file)
        }
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !installing },
        ),
    ) {
        val scrollState = rememberScrollState()
        Box(modifier = Modifier.imePadding()) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_skills_add),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.settings_skills_safety),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { filePickerLauncher?.launch() },
                    enabled = canSubmit && filePickerLauncher != null,
                    modifier = Modifier.fillMaxWidth().handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_skills_import))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.settings_skills_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(Res.string.settings_skills_add_github),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                KaiOutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(Res.string.settings_skills_github_url)) },
                    singleLine = true,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.settings_skills_github_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (installError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = installError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (isInstalling) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.settings_skills_installing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isInstalling, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.settings_skills_cancel))
                    }
                    TextButton(
                        onClick = { onInstallGitHub(url) },
                        enabled = url.isNotBlank() && canSubmit,
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(stringResource(Res.string.settings_skills_install))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
            VerticalScrollbarForScroll(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}
