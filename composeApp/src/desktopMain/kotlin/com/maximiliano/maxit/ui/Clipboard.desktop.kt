// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun clipEntryOfPlainText(text: String): ClipEntry = ClipEntry(StringSelection(text))
