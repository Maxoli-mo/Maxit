package com.maximiliano.maxit.ui.components

internal const val WEFT_CYCLE_DURATION_MILLIS = 2_400

/** Offsets in the approved 256-unit artboard: the rails move symmetrically. */
internal data class WeftPose(val railOffset: Int = 0, val keyOffset: Int = 0)

/**
 * Discrete, right-continuous poses for the Weft block assembly.
 * Keep in sync with src/wasmJsMain/resources/weft-assembly.svg.
 * The assembled holds span 0–30% and 80–100%; all movement is in 8-unit grid steps.
 * Both zero and the end of a cycle are assembled, so disabling motion is safe.
 */
internal fun weftPoseAt(playTimeMillis: Int): WeftPose {
    val time = playTimeMillis.coerceAtLeast(0) % WEFT_CYCLE_DURATION_MILLIS
    return when {
        time < 720 -> WeftPose()
        time < 840 -> WeftPose(keyOffset = -8)
        time < 960 -> WeftPose(keyOffset = -16)
        time < 1_080 -> WeftPose(keyOffset = -24)
        time < 1_200 -> WeftPose(railOffset = 8, keyOffset = -24)
        time < 1_440 -> WeftPose(railOffset = 16, keyOffset = -24)
        time < 1_560 -> WeftPose(railOffset = 8, keyOffset = -24)
        time < 1_680 -> WeftPose(keyOffset = -24)
        time < 1_800 -> WeftPose(keyOffset = -16)
        time < 1_920 -> WeftPose(keyOffset = -8)
        else -> WeftPose()
    }
}
