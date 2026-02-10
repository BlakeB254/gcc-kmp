package com.gcc.app.ui.gamification

import androidx.compose.animation.core.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlin.math.roundToInt

/**
 * Animated counter that rolls up from 0 to the target value.
 *
 * Used in profile cards for stats like "47 JOBS" and "$4,700 EARNED".
 * The count-up animation creates a slot-machine feel that triggers
 * dopamine — a key gamification pattern.
 */
@Composable
fun AnimatedCounter(
    targetValue: Int,
    prefix: String = "",
    suffix: String = "",
    durationMs: Int = 1000,
    delayMs: Int = 0,
    style: TextStyle = TextStyle.Default,
    modifier: Modifier = Modifier,
) {
    var animationPlayed by remember { mutableStateOf(false) }
    val animatedValue by animateIntAsState(
        targetValue = if (animationPlayed) targetValue else 0,
        animationSpec = tween(
            durationMillis = durationMs,
            delayMillis = delayMs,
            easing = FastOutSlowInEasing,
        ),
        label = "counter",
    )

    LaunchedEffect(targetValue) {
        animationPlayed = true
    }

    Text(
        text = "$prefix$animatedValue$suffix",
        style = style,
        modifier = modifier,
    )
}

/**
 * Animated dollar counter that formats cents to dollars.
 */
@Composable
fun AnimatedDollarCounter(
    totalCents: Int,
    durationMs: Int = 1200,
    delayMs: Int = 200,
    style: TextStyle = TextStyle.Default,
    modifier: Modifier = Modifier,
) {
    var animationPlayed by remember { mutableStateOf(false) }
    val animatedFloat by animateFloatAsState(
        targetValue = if (animationPlayed) totalCents / 100f else 0f,
        animationSpec = tween(
            durationMillis = durationMs,
            delayMillis = delayMs,
            easing = FastOutSlowInEasing,
        ),
        label = "dollar_counter",
    )

    LaunchedEffect(totalCents) {
        animationPlayed = true
    }

    val dollars = animatedFloat.roundToInt()
    Text(
        text = "$${"#,###".formatNumber(dollars)}",
        style = style,
        modifier = modifier,
    )
}

/** Simple number formatter that adds comma separators */
private fun String.formatNumber(value: Int): String {
    if (value < 1000) return value.toString()
    val str = value.toString()
    val sb = StringBuilder()
    str.reversed().forEachIndexed { index, c ->
        if (index > 0 && index % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.reverse().toString()
}
