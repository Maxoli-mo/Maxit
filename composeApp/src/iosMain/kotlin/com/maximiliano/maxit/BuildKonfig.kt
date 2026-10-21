// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual val isDebugBuild: Boolean = kotlin.native.Platform.isDebugBinary
