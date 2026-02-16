package com.gcc.admin.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminError
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminOutline
import com.gcc.admin.ui.theme.AdminPrimary
import com.gcc.admin.ui.theme.AdminSuccess
import com.gcc.admin.ui.theme.AdminTheme
import com.gcc.admin.viewmodel.RouteManagementViewModel
import com.gcc.shared.model.CameraMode
import com.gcc.shared.model.CheckpointType
import org.koin.compose.koinInject

// ── Friendly display labels ────────────────────────────────────────────────────

private fun CheckpointType.displayLabel(): String = when (this) {
    CheckpointType.ROUTE_START -> "Route Start"
    CheckpointType.PHOTO_POINT -> "Photo Point"
    CheckpointType.ALLEY_ENTRANCE -> "Alley Entrance"
    CheckpointType.ROUTE_END -> "Route End"
    CheckpointType.RECORD -> "Record (Camera)"
    CheckpointType.CLEAN -> "Clean"
    CheckpointType.VERIFY -> "Verify (Camera)"
    CheckpointType.RECORD_CLEAN_VERIFY -> "Record \u2192 Clean \u2192 Verify"
}

private fun CameraMode.displayLabel(): String = when (this) {
    CameraMode.PANORAMIC -> "Panoramic Scan"
    CameraMode.FOCUSED -> "Focused Shot"
    CameraMode.VIDEO -> "Video Capture"
}

/** Returns true if the checkpoint type requires a camera mode selector. */
private fun CheckpointType.needsCameraMode(): Boolean = when (this) {
    CheckpointType.RECORD,
    CheckpointType.VERIFY,
    CheckpointType.RECORD_CLEAN_VERIFY -> true
    else -> false
}

/** Parse a type string back to a CheckpointType enum value. */
private fun parseCheckpointType(value: String): CheckpointType =
    CheckpointType.entries.firstOrNull { it.name.lowercase() == value } ?: CheckpointType.PHOTO_POINT

/** Parse a cameraMode string back to a CameraMode enum value. */
private fun parseCameraMode(value: String?): CameraMode? =
    if (value == null) null else CameraMode.entries.firstOrNull { it.name.lowercase() == value }

// ── Main Screen ────────────────────────────────────────────────────────────────

@Composable
fun AdminRouteEditorScreen(routeId: Int?, onBack: () -> Unit) {
    val viewModel = koinInject<RouteManagementViewModel>()
    val routes by viewModel.routes.collectAsState()
    val editingRoute by viewModel.editingRoute.collectAsState()
    val editingCheckpoints by viewModel.editingCheckpoints.collectAsState()
    val editingBountyPrompts by viewModel.editingBountyPrompts.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val error by viewModel.error.collectAsState()

    // Initialize editing state if editing existing route
    LaunchedEffect(routeId) {
        if (routeId != null) {
            val route = routes.find { it.id == routeId }
            if (route != null) {
                viewModel.startEditRoute(route)
            }
        }
    }

    val route = editingRoute

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
    ) {
        // ── Header ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (routeId != null) "Edit Route: ${route?.code ?: ""}" else "New Route",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (isSaving) {
                Spacer(Modifier.width(AdminTheme.spacing.sm))
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        if (route != null) {
            // ── 3-Column Layout ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                // ── Left Column (35%): Route Details ─────────────────
                RouteDetailsPanel(
                    route = route,
                    viewModel = viewModel,
                    modifier = Modifier.weight(0.35f).fillMaxHeight(),
                )

                // ── Center Column (35%): Map Placeholder ─────────────
                Surface(
                    modifier = Modifier.weight(0.35f).fillMaxHeight(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = AdminTheme.elevation.card,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.dp, AdminOutline, MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Route Map",
                                style = MaterialTheme.typography.titleSmall,
                                color = AdminOnSurfaceVariant,
                            )
                            Spacer(Modifier.height(AdminTheme.spacing.xs))
                            Text(
                                "Canvas map integration pending",
                                style = MaterialTheme.typography.bodySmall,
                                color = AdminOnSurfaceVariant,
                            )
                            Spacer(Modifier.height(AdminTheme.spacing.sm))
                            Text(
                                "${editingCheckpoints.size} checkpoint(s) placed",
                                style = MaterialTheme.typography.labelMedium,
                                color = AdminPrimary,
                            )
                        }
                    }
                }

                // ── Right Column (30%): Checkpoints + Bounty Prompts ─
                Column(
                    modifier = Modifier.weight(0.30f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    // Checkpoint Editor
                    CheckpointEditorPanel(
                        checkpoints = editingCheckpoints,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )

                    // Bounty Prompts Editor
                    BountyPromptsPanel(
                        bountyPrompts = editingBountyPrompts,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth().weight(0.6f),
                    )
                }
            }

            // ── Save Button Row ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.padding(end = AdminTheme.spacing.md),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        viewModel.saveRoute()
                        onBack()
                    },
                    enabled = !isSaving && route.code.isNotBlank() && route.displayName.isNotBlank(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (routeId != null) "Save Changes" else "Create Route")
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

// ── Route Details Panel (Left Column) ──────────────────────────────────────────

@Composable
private fun RouteDetailsPanel(
    route: com.gcc.shared.model.AdminRouteRequest,
    viewModel: RouteManagementViewModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = AdminTheme.elevation.card,
    ) {
        LazyColumn(
            modifier = Modifier.padding(AdminTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
        ) {
            item {
                Text("Route Details", style = MaterialTheme.typography.titleMedium)
            }

            item {
                OutlinedTextField(
                    value = route.code,
                    onValueChange = { viewModel.updateField("code", it) },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = route.displayName,
                    onValueChange = { viewModel.updateField("displayName", it) },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = route.serviceAreaId.toString(),
                    onValueChange = { viewModel.updateField("serviceAreaId", it.toIntOrNull() ?: 0) },
                    label = { Text("Service Area ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
                ) {
                    OutlinedTextField(
                        value = route.distanceMeters?.toString() ?: "",
                        onValueChange = { viewModel.updateField("distanceMeters", it.toDoubleOrNull()) },
                        label = { Text("Distance (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = route.estimatedDurationMinutes?.toString() ?: "",
                        onValueChange = { viewModel.updateField("estimatedDurationMinutes", it.toIntOrNull()) },
                        label = { Text("Duration (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = route.payoutAmount?.toString() ?: "",
                    onValueChange = { viewModel.updateField("payoutAmount", it.toIntOrNull()) },
                    label = { Text("Payout (cents)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = route.imageUrl ?: "",
                    onValueChange = { viewModel.updateField("imageUrl", it.ifBlank { null }) },
                    label = { Text("Image URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Checkpoint Editor Panel (Right Column, Top) ────────────────────────────────

@Composable
private fun CheckpointEditorPanel(
    checkpoints: List<com.gcc.shared.model.AdminCheckpointRequest>,
    viewModel: RouteManagementViewModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = AdminTheme.elevation.card,
    ) {
        Column(modifier = Modifier.padding(AdminTheme.spacing.md)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Checkpoints (${checkpoints.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(
                    onClick = {
                        viewModel.addCheckpoint(
                            name = "Checkpoint ${checkpoints.size + 1}",
                            lat = 41.86,
                            lng = -87.72,
                        )
                    },
                ) {
                    Text("+ Add")
                }
            }

            Spacer(Modifier.height(AdminTheme.spacing.xs))

            if (checkpoints.isEmpty()) {
                Text(
                    "No checkpoints added",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                ) {
                    itemsIndexed(checkpoints) { index, cp ->
                        CheckpointItem(
                            index = index,
                            checkpoint = cp,
                            viewModel = viewModel,
                        )
                        if (index < checkpoints.lastIndex) {
                            HorizontalDivider(color = AdminOutline, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

// ── Single Checkpoint Item ─────────────────────────────────────────────────────

@Composable
private fun CheckpointItem(
    index: Int,
    checkpoint: com.gcc.shared.model.AdminCheckpointRequest,
    viewModel: RouteManagementViewModel,
) {
    val currentType = parseCheckpointType(checkpoint.type)
    val currentCameraMode = parseCameraMode(checkpoint.cameraMode)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AdminTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
    ) {
        // Row 1: Sequence number, editable name, delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
        ) {
            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.labelMedium,
                color = AdminOnSurfaceVariant,
            )
            OutlinedTextField(
                value = checkpoint.name,
                onValueChange = { viewModel.updateCheckpoint(index, name = it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).height(44.dp),
            )
            IconButton(
                onClick = { viewModel.removeCheckpoint(index) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove checkpoint",
                    modifier = Modifier.size(16.dp),
                    tint = AdminError,
                )
            }
        }

        // Row 2: Checkpoint type dropdown
        CheckpointTypeDropdown(
            selectedType = currentType,
            onTypeSelected = { newType ->
                viewModel.updateCheckpoint(index, type = newType)
                // Clear camera mode if the new type doesn't need it
                if (!newType.needsCameraMode()) {
                    viewModel.clearCheckpointCameraMode(index)
                }
            },
        )

        // Row 3: Camera mode dropdown (conditional)
        if (currentType.needsCameraMode()) {
            CameraModeDropdown(
                selectedMode = currentCameraMode,
                onModeSelected = { viewModel.updateCheckpoint(index, cameraMode = it) },
            )
        }

        // Row 4: Instructions field
        OutlinedTextField(
            value = checkpoint.instructions ?: "",
            onValueChange = { viewModel.updateCheckpoint(index, instructions = it) },
            label = { Text("Instructions") },
            singleLine = false,
            maxLines = 2,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )

        // Row 5: Checkpoint-level bounty prompts
        var newPromptText by remember(index) { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
        ) {
            OutlinedTextField(
                value = newPromptText,
                onValueChange = { newPromptText = it },
                label = { Text("Bounty prompt") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).height(44.dp),
            )
            TextButton(
                onClick = {
                    if (newPromptText.isNotBlank()) {
                        val updated = checkpoint.bountyPrompts + newPromptText.trim()
                        viewModel.updateCheckpoint(index, bountyPrompts = updated)
                        newPromptText = ""
                    }
                },
            ) {
                Text("+", style = MaterialTheme.typography.labelLarge, color = AdminPrimary)
            }
        }

        // Show existing bounty prompts as removable chips
        if (checkpoint.bountyPrompts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                checkpoint.bountyPrompts.forEachIndexed { promptIdx, prompt ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                    ) {
                        Text(
                            "\u2022 $prompt",
                            style = MaterialTheme.typography.labelSmall,
                            color = AdminOnSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val updated = checkpoint.bountyPrompts.toMutableList()
                                updated.removeAt(promptIdx)
                                viewModel.updateCheckpoint(index, bountyPrompts = updated)
                            },
                            modifier = Modifier.size(18.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove prompt",
                                modifier = Modifier.size(12.dp),
                                tint = AdminError,
                            )
                        }
                    }
                }
            }
        }

        // Coordinates label
        Text(
            "${String.format("%.4f", checkpoint.lat)}, ${String.format("%.4f", checkpoint.lng)}",
            style = MaterialTheme.typography.labelSmall,
            color = AdminOnSurfaceVariant,
        )
    }
}

// ── Checkpoint Type Dropdown ───────────────────────────────────────────────────

@Composable
private fun CheckpointTypeDropdown(
    selectedType: CheckpointType,
    onTypeSelected: (CheckpointType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Text(
                selectedType.displayLabel(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Select type",
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            CheckpointType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayLabel(), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── Camera Mode Dropdown ───────────────────────────────────────────────────────

@Composable
private fun CameraModeDropdown(
    selectedMode: CameraMode?,
    onModeSelected: (CameraMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Text(
                selectedMode?.displayLabel() ?: "Select camera mode",
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedMode != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    AdminOnSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Select camera mode",
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            CameraMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayLabel(), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── Bounty Prompts Panel (Right Column, Bottom) ────────────────────────────────

@Composable
private fun BountyPromptsPanel(
    bountyPrompts: List<com.gcc.shared.model.RouteBountyPrompt>,
    viewModel: RouteManagementViewModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = AdminTheme.elevation.card,
    ) {
        Column(modifier = Modifier.padding(AdminTheme.spacing.md)) {
            Text("Route Bounty Prompts", style = MaterialTheme.typography.titleSmall)

            Spacer(Modifier.height(AdminTheme.spacing.sm))

            // Add new bounty prompt form
            var newTitle by remember { mutableStateOf("") }
            var newDescription by remember { mutableStateOf("") }
            var newPointValue by remember { mutableStateOf("10") }

            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("Title") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(AdminTheme.spacing.xs))

            OutlinedTextField(
                value = newDescription,
                onValueChange = { newDescription = it },
                label = { Text("Description") },
                singleLine = false,
                maxLines = 2,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(AdminTheme.spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newPointValue,
                    onValueChange = { newPointValue = it },
                    label = { Text("Points") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(80.dp),
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (newTitle.isNotBlank() && newDescription.isNotBlank()) {
                            viewModel.addBountyPrompt(
                                title = newTitle.trim(),
                                description = newDescription.trim(),
                                pointValue = newPointValue.toIntOrNull() ?: 10,
                            )
                            newTitle = ""
                            newDescription = ""
                            newPointValue = "10"
                        }
                    },
                    enabled = newTitle.isNotBlank() && newDescription.isNotBlank(),
                ) {
                    Text("Add", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (bountyPrompts.isNotEmpty()) {
                Spacer(Modifier.height(AdminTheme.spacing.sm))
                HorizontalDivider(color = AdminOutline, thickness = 0.5.dp)
                Spacer(Modifier.height(AdminTheme.spacing.xs))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                ) {
                    itemsIndexed(bountyPrompts) { index, prompt ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    prompt.title,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    prompt.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AdminOnSurfaceVariant,
                                )
                            }
                            Text(
                                "${prompt.pointValue} pts",
                                style = MaterialTheme.typography.labelSmall,
                                color = AdminSuccess,
                            )
                            IconButton(
                                onClick = { viewModel.removeBountyPrompt(index) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove bounty prompt",
                                    modifier = Modifier.size(14.dp),
                                    tint = AdminError,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
