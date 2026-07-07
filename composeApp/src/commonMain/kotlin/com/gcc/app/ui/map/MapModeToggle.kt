package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*

enum class MapMode { MY_AREA, EXPLORER }

@Composable
fun MapModeToggle(
    currentMode: MapMode,
    onModeChange: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    PillChipSurface(modifier = modifier) {
        Row(
            modifier = Modifier.padding(3.dp),
        ) {
            MapMode.entries.forEach { mode ->
                val label = when (mode) {
                    MapMode.MY_AREA -> "My Area"
                    MapMode.EXPLORER -> "Explorer"
                }
                PillChipItem(
                    label = label,
                    selected = mode == currentMode,
                    activeColor = GccGreen,
                    onClick = { onModeChange(mode) },
                )
            }
        }
    }
}

/**
 * Explorer-lens toggle (P5): "Needs scanning" — same pill/Surface language as
 * [MapModeToggle] (shared [PillChipSurface]/[PillChipItem]) so the two chips
 * read as one family in the map's top chrome, just amber (matching the
 * priority lens's low→high color ramp) instead of green when on.
 */
@Composable
fun NeedsScanningToggleChip(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PillChipSurface(modifier = modifier) {
        Box(modifier = Modifier.padding(3.dp)) {
            PillChipItem(
                label = "Needs scanning",
                selected = active,
                activeColor = GccClaimed,
                onClick = onToggle,
            )
        }
    }
}

/** The white rounded elevated container shared by every pill chip in the map
 *  top chrome. */
@Composable
private fun PillChipSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
        color = Color.White,
        content = content,
    )
}

/** One segment of the map top chrome's pill-chip family: bold white text on
 *  [activeColor] when selected, muted on transparent otherwise. */
@Composable
private fun PillChipItem(
    label: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) activeColor else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) Color.White else TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
