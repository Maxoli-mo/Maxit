package com.maximiliano.maxit

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

internal data class DesktopDisplay(val scaleHint: Float?, val windowSize: DpSize) {
    // Native density belongs to the current window/monitor, never the startup screen.
    fun scaleFor(nativeScale: Float): Float = maxOf(nativeScale, scaleHint ?: 1f)
}

internal fun desktopDisplay(): DesktopDisplay {
    val graphics = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val nativeScale = graphics.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
    val bounds = graphics.maximumWindowBounds
    val linux = currentPlatform is Platform.Desktop.Linux
    val xftDpi = if (linux) {
        (Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Xft/DPI") as? Number)?.toFloat()
    } else {
        null
    }
    val scaleHint = desktopScaleHint(
        nativeScale = nativeScale,
        xftDpi = xftDpi,
        gdkScale = if (linux) System.getenv("GDK_SCALE")?.toFloatOrNull() else null,
        gdkDpiScale = if (linux) System.getenv("GDK_DPI_SCALE")?.toFloatOrNull() else null,
    )
    val initialScale = maxOf(nativeScale, scaleHint ?: 1f)
    return DesktopDisplay(scaleHint, desktopWindowSize(bounds.width, bounds.height, nativeScale, initialScale))
}

internal fun desktopDisplayScale(
    nativeScale: Float,
    xftDpi: Float? = null,
    gdkScale: Float? = null,
    gdkDpiScale: Float? = null,
): Float = maxOf(nativeScale, desktopScaleHint(xftDpi, gdkScale, gdkDpiScale, nativeScale) ?: 1f)

internal fun desktopScaleHint(
    xftDpi: Float? = null,
    gdkScale: Float? = null,
    gdkDpiScale: Float? = null,
    nativeScale: Float = 1f,
): Float? {
    // XSettings stores DPI in 1024ths. Under GNOME/XWayland AWT can report 1×
    // even when Xft reports 2×. These are absolute scales, not multipliers.
    // If AWT already detected HiDPI, keep following its per-window density instead
    // of turning the startup screen's XSettings value into a permanent minimum.
    val xftScale = if (nativeScale == 1f) {
        xftDpi?.div(1024f * 96f)?.takeIf { it.isFinite() && it in 0.5f..4f }
    } else {
        null
    }
    // Only an explicitly supplied, valid environment value overrides XSettings.
    val environmentScale = if (gdkScale != null || gdkDpiScale != null) {
        ((gdkScale ?: 1f) * (gdkDpiScale ?: 1f))
            .takeIf { it.isFinite() && it in 0.5f..4f }
    } else {
        null
    }
    return environmentScale ?: xftScale
}

internal fun desktopWindowSize(
    screenWidth: Int,
    screenHeight: Int,
    nativeScale: Float,
    displayScale: Float,
): DpSize {
    // Window sizing uses AWT's density, while content uses the corrected density.
    val correction = displayScale / nativeScale
    return DpSize(
        minOf(1100f * correction, screenWidth * 0.9f).dp,
        minOf(720f * correction, screenHeight * 0.85f).dp,
    )
}
