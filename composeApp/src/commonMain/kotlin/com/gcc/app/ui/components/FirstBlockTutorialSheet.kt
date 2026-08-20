package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapCenter
import com.gcc.app.ui.map.MapMode
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted
import com.gcc.shared.model.Coordinates

/**
 * §4 first-block-assignment tutorial: shown once (FirstRunStore
 * .tutorialBlockSeen) inside a ModalBottomSheet the first time an assignment
 * lands on the Dashboard. Route name, mini-map target, three steps, and an
 * "Open route" CTA into RouteDetailScreen.
 */
@Composable
fun FirstBlockTutorialSheet(
    routeName: String,
    mapCenter: MapCenter,
    routeCoordinates: List<List<Coordinates>>,
    currentLocation: Coordinates?,
    onOpenRoute: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Your first block is ready",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                "$routeName is assigned to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }

        // Mini-map target (same resolution the Dashboard preview uses)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(13.dp)),
        ) {
            GccMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = mapCenter.lat,
                centerLng = mapCenter.lng,
                zoom = mapCenter.zoom,
                currentLocation = currentLocation,
                routeCoordinates = routeCoordinates,
                showControls = false,
                mapMode = MapMode.MY_AREA,
            )
        }

        FirstBlockStep(
            number = 1,
            title = "Open the route",
            body = "Head to the route boundary — the app navigates you to the start.",
        )
        FirstBlockStep(
            number = 2,
            title = "Walk the boundary",
            body = "Follow the route line. Your lap is tracked as you go.",
        )
        FirstBlockStep(
            number = 3,
            title = "Mark litter",
            body = "Tap what you see — photos and locations are captured for you.",
        )

        GccButton(
            text = "Open route",
            onClick = onOpenRoute,
            variant = GccButtonVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FirstBlockStep(number: Int, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(GccGreen),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}
