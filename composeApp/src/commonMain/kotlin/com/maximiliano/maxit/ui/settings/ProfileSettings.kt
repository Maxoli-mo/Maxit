package com.maximiliano.maxit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maximiliano.maxit.data.MAX_PROFILE_NAME_CHARS
import com.maximiliano.maxit.data.UserProfile
import com.maximiliano.maxit.ui.KaiOutlinedTextField
import com.maximiliano.maxit.ui.handCursor
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.settings_agent_instructions
import maxit.composeapp.generated.resources.settings_agent_instructions_description
import maxit.composeapp.generated.resources.settings_custom_instructions_placeholder
import maxit.composeapp.generated.resources.settings_full_name
import maxit.composeapp.generated.resources.settings_full_name_description
import maxit.composeapp.generated.resources.settings_preferred_name
import maxit.composeapp.generated.resources.settings_preferred_name_description
import maxit.composeapp.generated.resources.settings_profile
import maxit.composeapp.generated.resources.settings_save_custom_instructions
import maxit.composeapp.generated.resources.settings_save_profile
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProfileContent(uiState: SettingsUiState, actions: SettingsActions) {
    StaggeredSettingsColumns(
        start = { SettingsCard { ProfileEditor(uiState.userProfile, actions.onSaveProfile) } },
        end = { SettingsCard { CustomInstructionsEditor(uiState.soulText, actions.onSaveSoul) } },
    )
}

@Composable
private fun ProfileEditor(profile: UserProfile, onSave: (UserProfile) -> Unit) {
    var fullName by rememberSaveable(profile.fullName) { mutableStateOf(profile.fullName) }
    var preferredName by rememberSaveable(profile.preferredName) { mutableStateOf(profile.preferredName) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.settings_profile), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        KaiOutlinedTextField(
            value = fullName,
            onValueChange = { if (it.length <= MAX_PROFILE_NAME_CHARS && it.none(Char::isISOControl)) fullName = it },
            label = { Text(stringResource(Res.string.settings_full_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("profile-full-name"),
        )
        Text(
            stringResource(Res.string.settings_full_name_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KaiOutlinedTextField(
            value = preferredName,
            onValueChange = { if (it.length <= MAX_PROFILE_NAME_CHARS && it.none(Char::isISOControl)) preferredName = it },
            label = { Text(stringResource(Res.string.settings_preferred_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("profile-preferred-name"),
        )
        Text(
            stringResource(Res.string.settings_preferred_name_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onSave(UserProfile(fullName.trim(), preferredName.trim())) },
            enabled = fullName.trim() != profile.fullName || preferredName.trim() != profile.preferredName,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).handCursor().testTag("save-profile"),
        ) {
            Text(stringResource(Res.string.settings_save_profile))
        }
    }
}

@Composable
private fun CustomInstructionsEditor(saved: String, onSave: (String) -> Unit) {
    var edited by rememberSaveable(saved) { mutableStateOf(saved) }
    val maxChars = 4000
    Column {
        Text(stringResource(Res.string.settings_agent_instructions), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.settings_agent_instructions_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        KaiOutlinedTextField(
            value = edited,
            onValueChange = { if (it.length <= maxChars || it.length < edited.length) edited = it },
            label = { Text(stringResource(Res.string.settings_agent_instructions)) },
            placeholder = { Text(stringResource(Res.string.settings_custom_instructions_placeholder)) },
            minLines = 6,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().testTag("custom-instructions"),
        )
        Text(
            "${edited.length}/$maxChars",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(edited.trim()) },
            enabled = edited != saved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).handCursor().testTag("save-custom-instructions"),
        ) {
            Text(stringResource(Res.string.settings_save_custom_instructions))
        }
    }
}
