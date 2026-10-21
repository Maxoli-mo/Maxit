// Modified for Maxit, 2026-10-20.
package com.maximiliano.maxit.inference

actual fun createLocalInferenceEngine(): LocalInferenceEngine? = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) LiteRTInferenceEngine() else null
