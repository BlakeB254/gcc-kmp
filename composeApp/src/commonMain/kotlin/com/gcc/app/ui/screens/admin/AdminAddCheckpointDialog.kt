package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.AdminCheckpointRequest

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminAddCheckpointDialog(
    routeId: Int,
    nextSequenceOrder: Int,
    currentLat: Double,
    currentLng: Double,
    onDismiss: () -> Unit,
    onSave: (AdminCheckpointRequest) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var photoRequired by remember { mutableStateOf(true) }
    var selectedType by remember { mutableStateOf("photo_point") }
    var error by remember { mutableStateOf<String?>(null) }

    val checkpointTypes = listOf("route_start", "photo_point", "alley_entrance", "route_end", "record", "clean", "verify", "record_clean_verify")

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        title = { Text("Add Checkpoint #$nextSequenceOrder", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Current location
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = GccGreen.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "\uD83D\uDCCD ${formatCoord(currentLat)}, ${formatCoord(currentLng)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GccGreen,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Checkpoint Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("Type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    checkpointTypes.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.replace('_', ' '), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GccGreen.copy(alpha = 0.15f),
                                selectedLabelColor = GccGreen,
                            ),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { photoRequired = !photoRequired },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = photoRequired,
                        onCheckedChange = { photoRequired = it },
                        colors = CheckboxDefaults.colors(checkedColor = GccGreen),
                    )
                    Text("Photo required at this checkpoint", style = MaterialTheme.typography.bodySmall)
                }

                error?.let {
                    Text(it, color = GccError, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Name is required"
                        return@Button
                    }
                    onSave(
                        AdminCheckpointRequest(
                            name = name.trim(),
                            lat = currentLat,
                            lng = currentLng,
                            sequenceOrder = nextSequenceOrder,
                            photoRequired = photoRequired,
                            type = selectedType,
                        ),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
            ) {
                Text("Add Checkpoint")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
