// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.data

import com.maximiliano.maxit.network.UiError

/**
 * Emitted while [DataRepository.ask] walks the configured fallback chain. Only set when a
 * service has just failed and the loop is moving on — the UI keeps showing this status while
 * the next service is being tried, so silent fallbacks are visible to the user.
 */
data class FallbackStatus(
    val serviceName: String,
    val errorReason: UiError,
    val nextServiceName: String? = null,
)
