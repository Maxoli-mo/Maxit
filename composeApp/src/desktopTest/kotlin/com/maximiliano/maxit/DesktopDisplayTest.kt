package com.maximiliano.maxit

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDisplayTest {
    @Test
    fun correctsMissingXwaylandDensity() {
        assertEquals(2f, desktopDisplayScale(1f, xftDpi = 192f * 1024f))
    }

    @Test
    fun doesNotDoubleNativeScaling() {
        assertEquals(2f, desktopDisplayScale(2f, xftDpi = 192f * 1024f, gdkScale = 2f))
        assertEquals(2f, desktopDisplayScale(2f, gdkScale = 2f))
    }

    @Test
    fun preservesStandardAndFractionalDisplays() {
        assertEquals(1f, desktopDisplayScale(1f))
        assertEquals(1.5f, desktopDisplayScale(1.5f))
        assertEquals(1.25f, desktopDisplayScale(1f, xftDpi = 120f * 1024f))
    }

    @Test
    fun fallsBackToEnvironmentWhenXftIsUnavailable() {
        assertEquals(2f, desktopDisplayScale(1f, gdkScale = 2f))
        assertEquals(1f, desktopDisplayScale(1f, gdkScale = 2f, gdkDpiScale = 0.5f))
        assertEquals(1.5f, desktopDisplayScale(1f, xftDpi = -1f, gdkScale = 1.5f))
    }

    @Test
    fun ignoresInvalidScaleHints() {
        assertEquals(1f, desktopDisplayScale(1f, xftDpi = Float.NaN, gdkScale = Float.POSITIVE_INFINITY))
        assertEquals(1f, desktopDisplayScale(1f, xftDpi = 0f, gdkScale = -2f))
    }

    @Test
    fun explicitEnvironmentOverridesXftWithoutMultiplyingNativeDensity() {
        assertEquals(2f, desktopDisplayScale(1f, xftDpi = 96f * 1024f, gdkScale = 2f))
        assertEquals(2f, desktopDisplayScale(2f, xftDpi = 96f * 1024f, gdkScale = 2f))
        assertEquals(1f, desktopDisplayScale(1f, xftDpi = 192f * 1024f, gdkScale = 1f))
        assertEquals(1f, desktopDisplayScale(1f, xftDpi = 192f * 1024f, gdkScale = 2f, gdkDpiScale = 0.5f))
        assertEquals(1.5f, desktopDisplayScale(1f, xftDpi = 96f * 1024f, gdkDpiScale = 1.5f))
    }

    @Test
    fun missingOrInvalidEnvironmentDoesNotMaskXft() {
        assertEquals(2f, desktopDisplayScale(1f, xftDpi = 192f * 1024f))
        assertEquals(2f, desktopDisplayScale(1f, xftDpi = 192f * 1024f, gdkScale = Float.NaN))
        assertEquals(2f, desktopDisplayScale(1f, xftDpi = 192f * 1024f, gdkScale = 0f))
    }

    @Test
    fun nativeDensityFollowsMonitorChangesInBothDirections() {
        val scaleHint = desktopScaleHint(nativeScale = 2f, xftDpi = 192f * 1024f)
        assertNull(scaleHint)
        val display = DesktopDisplay(scaleHint, desktopWindowSize(1920, 1080, 2f, 2f))

        assertEquals(2f, display.scaleFor(2f))
        assertEquals(1f, display.scaleFor(1f))
        assertEquals(1.5f, display.scaleFor(1.5f))
        assertEquals(2f, display.scaleFor(2f))
    }

    @Test
    fun nativeDensityWithoutLinuxHintsCanDecrease() {
        val display = DesktopDisplay(desktopScaleHint(), desktopWindowSize(1920, 1080, 2f, 2f))

        assertEquals(2f, display.scaleFor(2f))
        assertEquals(1f, display.scaleFor(1f))
    }

    @Test
    fun xwaylandHintStillCompensatesForMissingNativeDensity() {
        val display = DesktopDisplay(desktopScaleHint(xftDpi = 192f * 1024f), DpSize(1100.dp, 720.dp))

        assertEquals(2f, display.scaleFor(1f))
        assertEquals(2f, display.scaleFor(2f))
        assertEquals(3f, display.scaleFor(3f))
    }

    @Test
    fun compensatesWindowSizeForMissingNativeDensity() {
        assertEquals(DpSize(2200.dp, 1440.dp), desktopWindowSize(3072, 1728, 1f, 2f))
        assertEquals(DpSize(1100.dp, 720.dp), desktopWindowSize(1920, 1080, 1f, 1f))
        assertEquals(DpSize(1100.dp, 720.dp), desktopWindowSize(1920, 1080, 2f, 2f))
    }

    @Test
    fun fitsSmallScreens() {
        val size = desktopWindowSize(1024, 600, 1f, 1f)
        assertTrue(size.width.value <= 1024 * 0.9f)
        assertTrue(size.height.value <= 600 * 0.85f)
    }
}
