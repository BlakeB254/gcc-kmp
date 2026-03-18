package com.gcc.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.shared.model.ScanHotspot
import com.gcc.app.ui.theme.*

/**
 * Bottom-slide alert card shown when the worker is within 20m of an uncleaned hotspot.
 *
 * Presents hotspot details (trash type, severity, distance) with a CLEAN action
 * button and a Skip text button.
 */
@Composable
fun ProximityAlertCard(
    hotspot: ScanHotspot?,
    distanceMeters: Double?,
    onClean: (ScanHotspot) -> Unit,
    onSkip: (ScanHotspot) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = hotspot != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        val item = hotspot ?: return@AnimatedVisibility

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = GccError.copy(alpha = 0.95f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hotspot Nearby",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    distanceMeters?.let { dist ->
                        Text(
                            text = "${dist.toInt()}m away",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }

                // Scout photo placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\uD83D\uDDBC\uFE0F  Scout Photo",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }

                // Details row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Trash types
                    if (item.trashTypes.isNotEmpty()) {
                        Column {
                            Text(
                                text = "TYPE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                            Text(
                                text = item.trashTypes.take(2).joinToString(", ") {
                                    it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                            )
                        }
                    }

                    // Severity
                    Column {
                        Text(
                            text = "SEVERITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "${"★".repeat(item.severity.coerceIn(1, 5))}${
                                "☆".repeat((5 - item.severity).coerceIn(0, 4))
                            }",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // CLEAN primary button
                    Button(
                        onClick = { onClean(item) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = GccError,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = "CLEAN",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    // Skip text button
                    TextButton(
                        onClick = { onSkip(item) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White.copy(alpha = 0.85f),
                        ),
                    ) {
                        Text(
                            text = "Skip",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
