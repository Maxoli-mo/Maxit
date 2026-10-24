package com.maximiliano.maxit.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/** Null means still pending; unchanged text on rejection or after a later edit is preserved. */
internal fun finalizedComposerDraft(
    submittedText: String,
    currentText: String,
    accepted: Boolean,
    settled: Boolean,
): String? = when {
    accepted -> if (currentText == submittedText) "" else currentText
    settled -> currentText
    else -> null
}

/** Remember beside the draft, not inside the conditionally mounted composer. */
@Composable
internal fun rememberComposerSubmit(
    uiState: ChatUiState,
    textState: () -> TextFieldValue,
    onTextStateChange: (TextFieldValue) -> Unit,
): (String) -> Unit {
    var submittedText by rememberSaveable { mutableStateOf<String?>(null) }
    var previousInputOwnerId by rememberSaveable { mutableStateOf(uiState.inputOwnerId) }
    var previousAcceptedInputId by rememberSaveable { mutableStateOf(uiState.acceptedInputId) }
    var previousSettledInputId by rememberSaveable { mutableStateOf(uiState.settledInputId) }
    LaunchedEffect(uiState.inputOwnerId, uiState.acceptedInputId, uiState.settledInputId, submittedText) {
        val pending = submittedText ?: return@LaunchedEffect
        // Restored drafts cannot borrow acknowledgement counters from a new view model.
        if (uiState.inputOwnerId != previousInputOwnerId) {
            submittedText = null
            return@LaunchedEffect
        }
        val currentText = textState().text
        val finalized = finalizedComposerDraft(
            submittedText = pending,
            currentText = currentText,
            accepted = uiState.acceptedInputId != previousAcceptedInputId,
            settled = uiState.settledInputId != previousSettledInputId,
        ) ?: return@LaunchedEffect
        if (finalized != currentText) onTextStateChange(TextFieldValue(finalized))
        submittedText = null
    }
    return { text ->
        if (!uiState.isLoading && submittedText == null && text.isNotBlank()) {
            submittedText = text
            previousInputOwnerId = uiState.inputOwnerId
            previousAcceptedInputId = uiState.acceptedInputId
            previousSettledInputId = uiState.settledInputId
            uiState.actions.ask(text.trim())
        }
    }
}
