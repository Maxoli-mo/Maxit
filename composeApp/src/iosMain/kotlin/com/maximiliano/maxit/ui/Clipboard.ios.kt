// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun clipEntryOfPlainText(text: String): ClipEntry = ClipEntry.withPlainText(text)
