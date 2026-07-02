package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn vector icon set for the dashboard quick actions. All icons share
 * the same stroke weight, node-dot language, and take their accent color from
 * the button they sit in, so the row reads as one family instead of a grab-bag
 * of emoji.
 */

private fun DrawScope.iconStroke() = 1.6.dp.toPx()

/**
 * Environment scan, after the reference art: a phone on the left projects a
 * scan beam rightward that washes over a wireframe tree — the device is
 * digitizing the environment. Beam and phone render in [beamColor] (scan
 * blue); the tree renders in [natureColor] (GCC green) with mesh lines.
 */
@Composable
fun EnvironmentScanIcon(
    beamColor: Color,
    natureColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        val stroke = iconStroke()

        // ── Phone (left), portrait rounded rect with camera dot ──
        val phoneLeft = w * 0.02f
        val phoneTop = h * 0.24f
        val phoneW = w * 0.17f
        val phoneH = h * 0.52f
        drawRoundRect(
            color = beamColor,
            topLeft = Offset(phoneLeft, phoneTop),
            size = androidx.compose.ui.geometry.Size(phoneW, phoneH),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
        drawCircle(
            color = beamColor,
            radius = 0.9.dp.toPx(),
            center = Offset(phoneLeft + phoneW / 2f, phoneTop + phoneH * 0.16f),
        )

        // ── Beam: rays fanning from the phone's emitter toward the tree ──
        val emitter = Offset(phoneLeft + phoneW, h * 0.50f)
        val rayTargets = listOf(
            Offset(w * 0.98f, h * 0.10f),
            Offset(w * 0.99f, h * 0.32f),
            Offset(w * 1.00f, h * 0.50f),
            Offset(w * 0.99f, h * 0.68f),
            Offset(w * 0.98f, h * 0.90f),
        )
        rayTargets.forEachIndexed { i, target ->
            val edge = if (i == 2) 1f else 0.6f
            drawLine(
                color = beamColor.copy(alpha = edge * 0.85f),
                start = emitter,
                end = target,
                strokeWidth = stroke * if (i == 2) 0.9f else 0.6f,
            )
        }
        // Emitter pulse rings
        listOf(0.10f, 0.17f).forEach { r ->
            drawArc(
                color = beamColor.copy(alpha = 0.8f),
                startAngle = -70f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(emitter.x - w * r, emitter.y - w * r),
                size = androidx.compose.ui.geometry.Size(w * r * 2, w * r * 2),
                style = Stroke(stroke * 0.6f),
            )
        }

        // ── Wireframe tree (right), inside the beam ──
        val canopyC = Offset(w * 0.72f, h * 0.34f)
        val canopyR = w * 0.21f
        drawCircle(color = natureColor, radius = canopyR, center = canopyC, style = Stroke(stroke))
        // Canopy mesh: two horizontal chords + one vertical arc line
        listOf(-0.45f, 0.25f).forEach { dy ->
            val yy = canopyC.y + canopyR * dy
            val half = canopyR * kotlin.math.sqrt(1f - dy * dy)
            drawLine(
                color = natureColor.copy(alpha = 0.65f),
                start = Offset(canopyC.x - half, yy),
                end = Offset(canopyC.x + half, yy),
                strokeWidth = stroke * 0.55f,
            )
        }
        drawArc(
            color = natureColor.copy(alpha = 0.65f),
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(canopyC.x - canopyR * 0.45f, canopyC.y - canopyR),
            size = androidx.compose.ui.geometry.Size(canopyR * 0.9f, canopyR * 2f),
            style = Stroke(stroke * 0.55f),
        )
        // Trunk + root flare
        val trunkTop = Offset(canopyC.x, canopyC.y + canopyR)
        val trunkBase = Offset(canopyC.x, h * 0.82f)
        drawLine(color = natureColor, start = trunkTop, end = trunkBase, strokeWidth = stroke)
        drawLine(
            color = natureColor,
            start = trunkBase,
            end = Offset(trunkBase.x - w * 0.08f, h * 0.90f),
            strokeWidth = stroke * 0.8f,
        )
        drawLine(
            color = natureColor,
            start = trunkBase,
            end = Offset(trunkBase.x + w * 0.08f, h * 0.90f),
            strokeWidth = stroke * 0.8f,
        )

        // ── Ground line under the scene ──
        drawArc(
            color = natureColor.copy(alpha = 0.7f),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.78f),
            size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.5f),
            style = Stroke(stroke * 0.6f),
        )
    }
}

/**
 * Bounties: a crosshair target locked on a reward node — claimable work with
 * a prize at the center.
 */
@Composable
fun BountyTargetIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val stroke = iconStroke()
        val c = Offset(w / 2f, h / 2f)
        val rOuter = w * 0.38f
        val rInner = w * 0.22f

        drawCircle(color = color, radius = rOuter, center = c, style = Stroke(stroke))
        drawCircle(color = color.copy(alpha = 0.7f), radius = rInner, center = c, style = Stroke(stroke * 0.8f))
        drawCircle(color = color, radius = 2.4.dp.toPx(), center = c)

        // Crosshair ticks extending past the outer ring
        val tick = w * 0.14f
        listOf(
            Offset(c.x, c.y - rOuter - tick / 2) to Offset(c.x, c.y - rOuter + tick / 2),
            Offset(c.x, c.y + rOuter - tick / 2) to Offset(c.x, c.y + rOuter + tick / 2),
            Offset(c.x - rOuter - tick / 2, c.y) to Offset(c.x - rOuter + tick / 2, c.y),
            Offset(c.x + rOuter - tick / 2, c.y) to Offset(c.x + rOuter + tick / 2, c.y),
        ).forEach { (a, b) ->
            drawLine(color = color, start = a, end = b, strokeWidth = stroke)
        }
    }
}

/** Wallet: layered card with clasp plus an overlapping coin. */
@Composable
fun WalletCardIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val stroke = iconStroke()

        // Back card peeking out
        drawRoundRect(
            color = color.copy(alpha = 0.5f),
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.42f),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(stroke * 0.8f),
        )
        // Front card
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.32f),
            size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.46f),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(stroke),
        )
        // Clasp notch on the front card
        drawCircle(
            color = color,
            radius = 1.8.dp.toPx(),
            center = Offset(w * 0.64f, h * 0.55f),
        )

        // Coin overlapping bottom-right, with radial mint marks
        val coin = Offset(w * 0.80f, h * 0.72f)
        val coinR = w * 0.17f
        drawCircle(color = color, radius = coinR, center = coin, style = Stroke(stroke))
        for (i in 0 until 8) {
            val a = (i * 45f) * (PI / 180f).toFloat()
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(coin.x + (coinR * 0.55f) * cos(a), coin.y + (coinR * 0.55f) * sin(a)),
                end = Offset(coin.x + (coinR * 0.82f) * cos(a), coin.y + (coinR * 0.82f) * sin(a)),
                strokeWidth = stroke * 0.6f,
            )
        }
    }
}

/** Rankings: podium with a star rising off the first-place column. */
@Composable
fun RankingsPodiumIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val stroke = iconStroke()
        val baseY = h * 0.90f

        // Podium columns: 2nd, 1st, 3rd
        val colW = w * 0.24f
        val cols = listOf(
            Triple(w * 0.06f, h * 0.42f, 0.55f),  // silver
            Triple(w * 0.38f, h * 0.26f, 1f),     // gold (center, tallest)
            Triple(w * 0.70f, h * 0.52f, 0.55f),  // bronze
        )
        cols.forEach { (x, top, alpha) ->
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(x, top),
                size = androidx.compose.ui.geometry.Size(colW, baseY - top),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
                style = Stroke(stroke),
            )
        }

        // Five-point star above the center column
        val starC = Offset(w * 0.50f, h * 0.12f)
        val rOut = w * 0.11f
        val rIn = rOut * 0.42f
        val star = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) rOut else rIn
            val a = (-90f + i * 36f) * (PI / 180f).toFloat()
            val p = Offset(starC.x + r * cos(a), starC.y + r * sin(a))
            if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
        }
        star.close()
        drawPath(path = star, color = color)
    }
}

/** Community: a network of member nodes — people connected, not a newspaper. */
@Composable
fun CommunityNetworkIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val stroke = iconStroke()

        val top = Offset(w * 0.50f, h * 0.18f)
        val left = Offset(w * 0.16f, h * 0.72f)
        val right = Offset(w * 0.84f, h * 0.72f)

        // Connection lines first (under the nodes)
        listOf(top to left, top to right, left to right).forEach { (a, b) ->
            drawLine(
                color = color.copy(alpha = 0.6f),
                start = a,
                end = b,
                strokeWidth = stroke * 0.8f,
            )
        }

        // Member nodes: head circle + shoulder arc
        listOf(top, left, right).forEach { c ->
            drawCircle(color = color, radius = 3.2.dp.toPx(), center = c, style = Stroke(stroke))
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - 4.6.dp.toPx(), c.y + 2.4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(9.2.dp.toPx(), 7.dp.toPx()),
                style = Stroke(stroke),
            )
        }
    }
}
