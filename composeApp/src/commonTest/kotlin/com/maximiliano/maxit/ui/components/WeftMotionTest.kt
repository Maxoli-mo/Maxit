package com.maximiliano.maxit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeftMotionTest {
    @Test
    fun matchesApprovedSvgPosesAndHoldsUntilEachBoundary() {
        // Percentages and offsets are the pose metadata in the approved assemble.svg.
        val poses = listOf(
            Triple(0, 0, 0),
            Triple(30, 0, -8),
            Triple(35, 0, -16),
            Triple(40, 0, -24),
            Triple(45, 8, -24),
            Triple(50, 16, -24),
            Triple(60, 8, -24),
            Triple(65, 0, -24),
            Triple(70, 0, -16),
            Triple(75, 0, -8),
            Triple(80, 0, 0),
            Triple(100, 0, 0),
        )
        assertEquals(2_400, WEFT_CYCLE_DURATION_MILLIS)
        poses.zipWithNext { current, next ->
            val start = current.first * WEFT_CYCLE_DURATION_MILLIS / 100
            val end = next.first * WEFT_CYCLE_DURATION_MILLIS / 100
            val expected = WeftPose(current.second, current.third)
            for (time in start until end) {
                assertEquals(expected, weftPoseAt(time), "Pose at ${time}ms")
            }
        }
    }

    @Test
    fun endpointsUsedForInitialAndDisabledMotionAreAssembled() {
        assertEquals(WeftPose(), weftPoseAt(0))
        assertEquals(WeftPose(), weftPoseAt(WEFT_CYCLE_DURATION_MILLIS))
        assertEquals(WeftPose(), weftPoseAt(WEFT_CYCLE_DURATION_MILLIS - 1))
    }

    @Test
    fun repeatsOnTheGridAndOnlySeparatesRailsWithTheKeyLifted() {
        for (time in 0 until WEFT_CYCLE_DURATION_MILLIS) {
            val pose = weftPoseAt(time)
            assertEquals(pose, weftPoseAt(time + WEFT_CYCLE_DURATION_MILLIS))
            assertEquals(0, pose.railOffset % 8)
            assertEquals(0, pose.keyOffset % 8)
            assertTrue(pose.railOffset in 0..16)
            assertTrue(pose.keyOffset in -24..0)
            if (pose.railOffset != 0) assertEquals(-24, pose.keyOffset)
        }
    }
}
