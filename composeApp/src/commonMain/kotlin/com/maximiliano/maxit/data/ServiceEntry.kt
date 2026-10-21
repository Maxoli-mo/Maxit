// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.data

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.DrawableResource

@Immutable
data class ServiceEntry(
    val instanceId: String,
    val serviceId: String,
    val serviceName: String,
    val modelId: String,
    val icon: DrawableResource,
)
