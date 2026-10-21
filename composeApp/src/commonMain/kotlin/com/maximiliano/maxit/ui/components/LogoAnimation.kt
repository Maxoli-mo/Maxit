// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The approved Weft assembly, on a transparent 256-unit artboard. */
@Composable
fun LogoAnimation(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    // InfiniteTransition applies Compose's MotionDurationScale and suspends at zero.
    // Its target time maps to the assembled pose, including when motion is disabled.
    val transition = rememberInfiniteTransition(label = "Weft assembly")
    val playTimeMillis by transition.animateFloat(
        initialValue = 0f,
        targetValue = WEFT_CYCLE_DURATION_MILLIS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(WEFT_CYCLE_DURATION_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Weft clock",
    )
    // Only pose changes invalidate drawing; the clock never interpolates the geometry.
    val pose by remember { derivedStateOf { weftPoseAt(playTimeMillis.toInt()) } }
    val leftRail = remember {
        Path().apply {
            moveTo(48f, 64f)
            lineTo(176f, 64f)
            lineTo(176f, 96f)
            lineTo(112f, 96f)
            lineTo(112f, 128f)
            lineTo(80f, 128f)
            lineTo(80f, 160f)
            lineTo(48f, 160f)
            close()
        }
    }
    val rightRail = remember {
        // The source's 180-degree counterpart is baked into these vertices, not animated.
        Path().apply {
            moveTo(208f, 192f)
            lineTo(80f, 192f)
            lineTo(80f, 160f)
            lineTo(144f, 160f)
            lineTo(144f, 128f)
            lineTo(176f, 128f)
            lineTo(176f, 96f)
            lineTo(208f, 96f)
            close()
        }
    }
    Canvas(modifier = modifier.size(size)) {
        val artboardSize = this.size.minDimension
        val artboardScale = artboardSize / 256f
        val currentPose = pose
        withTransform({
            translate((this@Canvas.size.width - artboardSize) / 2f, (this@Canvas.size.height - artboardSize) / 2f)
            scale(artboardScale, artboardScale, pivot = Offset.Zero)
        }) {
            translate(left = -currentPose.railOffset.toFloat()) {
                drawPath(leftRail, Color(0xFF643CAF))
            }
            translate(left = currentPose.railOffset.toFloat()) {
                drawPath(rightRail, Color(0xFF9471D3))
            }
            // Keep the bridge fixed and draw the lifting key above it, as in assemble.svg.
            drawRect(Color(0xFF643CAF), topLeft = Offset(96f, 112f), size = Size(64f, 32f))
            drawRect(
                Color(0xFFB79AE6),
                topLeft = Offset(112f, 112f + currentPose.keyOffset),
                size = Size(32f, 32f),
            )
        }
    }
}
