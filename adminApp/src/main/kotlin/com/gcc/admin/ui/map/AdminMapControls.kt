package com.gcc.admin.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.ui.theme.AdminPrimary
import com.gcc.admin.ui.theme.AdminOnSurface

@Composable
fun AdminMapControls(
    currentZoom: Double,
    layerState: AdminMapState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onPresetSelect: (Double) -> Unit,
    onToggleLayer: (LayerType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // --- Zoom in/out buttons ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZoomButton(text = "+", onClick = onZoomIn)
            ZoomButton(text = "-", onClick = onZoomOut)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- Preset zoom bar ---
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MapControlBg,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ZoomPresetButton("US", ZOOM_COUNTRY, currentZoom, onPresetSelect)
                ZoomPresetButton("City", ZOOM_CITY, currentZoom, onPresetSelect)
                ZoomPresetButton("Area", ZOOM_NEIGHBORHOOD, currentZoom, onPresetSelect)
                ZoomPresetButton("Route", ZOOM_ROUTE, currentZoom, onPresetSelect)
                ZoomPresetButton("Street", ZOOM_STREET, currentZoom, onPresetSelect)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- Layer toggles ---
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MapControlBg,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LayerToggle("Areas", layerState.showCommunityAreas) { onToggleLayer(LayerType.COMMUNITY_AREAS) }
                LayerToggle("Blocks", layerState.showBlocks) { onToggleLayer(LayerType.BLOCKS) }
                LayerToggle("Routes", layerState.showRoutes) { onToggleLayer(LayerType.ROUTES) }
                LayerToggle("Spots", layerState.showSpots) { onToggleLayer(LayerType.SPOTS) }
                LayerToggle("Bounties", layerState.showBounties) { onToggleLayer(LayerType.BOUNTIES) }
                LayerToggle("Grid", layerState.showGrid) { onToggleLayer(LayerType.GRID) }
            }
        }
    }
}

@Composable
private fun ZoomButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MapControlBg,
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = MapControlIcon,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ZoomPresetButton(
    label: String,
    targetZoom: Double,
    currentZoom: Double,
    onSelect: (Double) -> Unit,
) {
    val isActive = kotlin.math.abs(currentZoom - targetZoom) < 1.5
    val bgColor = if (isActive) AdminPrimary else MapControlBg
    val textColor = if (isActive) androidx.compose.ui.graphics.Color.White else MapControlIcon

    Box(
        modifier = Modifier
            .width(42.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable { onSelect(targetZoom) }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 9.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LayerToggle(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = AdminPrimary,
                uncheckedColor = MapControlIcon.copy(alpha = 0.5f),
                checkmarkColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = AdminOnSurface,
            fontSize = 10.sp,
        )
    }
}
