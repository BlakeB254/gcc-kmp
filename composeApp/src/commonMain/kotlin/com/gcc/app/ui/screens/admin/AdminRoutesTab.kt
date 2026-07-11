package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.theme.*
import com.gcc.shared.geo.routePathCoordinates
import com.gcc.shared.model.AdminRouteRequest
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.Route
import com.gcc.shared.model.Worker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminRoutesTab(
    routes: List<Route>,
    workers: List<Worker>,
    currentWorker: Worker? = null,
    onRouteClick: (Int) -> Unit,
    onStatusChange: (Route, AdminRouteStatusAction, (Boolean, String?) -> Unit) -> Unit,
    updatingRouteStatusIds: Set<Int>,
    routeStatusActionError: String?,
    onCreateRoute: (AdminRouteRequest) -> Unit = {},
    onAssignRoute: (routeId: Int, workerId: Int, isTest: Boolean, onResult: (Boolean) -> Unit) -> Unit = { _, _, _, onResult -> onResult(false) },
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var routeToAssign by remember { mutableStateOf<Route?>(null) }
    var assignmentMessage by remember { mutableStateOf<String?>(null) }
    val pickerWorkers = remember(workers, currentWorker) {
        (listOfNotNull(currentWorker) + workers).distinctBy { it.id }
    }

    if (showCreateDialog) {
        CreateRouteDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { request ->
                onCreateRoute(request)
                showCreateDialog = false
            },
        )
    }

    routeToAssign?.let { route ->
        AssignRouteDialog(
            route = route,
            workers = pickerWorkers,
            currentWorkerId = currentWorker?.id,
            onDismiss = { routeToAssign = null },
            onAssign = { worker, isTest, onResult ->
                onAssignRoute(route.id, worker.id, isTest) { success ->
                    if (success) {
                        val mode = if (isTest) "test" else "real"
                        assignmentMessage = "${route.displayName} assigned to ${worker.name} ($mode)"
                        routeToAssign = null
                    }
                    onResult(success)
                }
            },
        )
    }

    // Compute route coordinates for the map overlay
    val routeCoordinates: List<List<Coordinates>> = remember(routes) {
        routes.map { it.adminPathCoordinates() }
    }

    val serviceArea = remember { chicagoCommunityAreas.find { it.isServiceArea } }
    val centerLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 } ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
    val centerLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 } ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Primary route controls ───────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GccGreen,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("+ Create Route", fontWeight = FontWeight.SemiBold)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "${routes.size} routes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }

        assignmentMessage?.let { message ->
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = GccGreen.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = GccGreen,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { assignmentMessage = null }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        // ── Map overview ──────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
            ) {
                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = MapStyles.ZOOM_SERVICE_AREA,
                    routeCoordinates = routeCoordinates,
                    enableGestures = false,
                )
                // Route count overlay
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        "${routes.size} routes",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ── Empty state ───────────────────────────────────────────────
        if (routes.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "\uD83D\uDDFA\nNo routes yet — tap Create to add one",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── Route list ────────────────────────────────────────────────
        items(routes, key = { it.id }) { route ->
            val distMi = route.distanceMeters?.let { m -> val mi = (m / 1609.34 * 10).toInt() / 10.0; "${mi} mi" } ?: "N/A"

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            val imageUrl = route.imageUrl?.takeIf { it.isNotBlank() }
                            if (imageUrl != null) {
                                NetworkImage(
                                    url = imageUrl,
                                    contentDescription = route.displayName,
                                    decodeWidth = 160,
                                    decodeHeight = 160,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    "\uD83D\uDDFA\uFE0F",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                route.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (route.blockBoundary != null) "$distMi \u2022 boundary set" else "$distMi \u2022 no boundary yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        route.payoutAmount?.let { cents ->
                            Text(
                                "$${cents / 100}",
                                style = MaterialTheme.typography.labelLarge,
                                color = GccGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    AdminRouteStatusControls(
                        route = route,
                        isUpdating = route.id in updatingRouteStatusIds,
                        actionError = routeStatusActionError,
                        onStatusAction = { action, onResult ->
                            onStatusChange(route, action, onResult)
                        },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onRouteClick(route.id) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text("Open", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { routeToAssign = route },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GccSky,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Assign", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Bottom spacing for pull-to-refresh
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun CreateRouteDialog(
    onDismiss: () -> Unit,
    onCreate: (AdminRouteRequest) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Create Route", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Code auto-generated \u2022 $100 payout",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Route Name") },
                    placeholder = { Text("e.g. Pulaski North Block") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "After creating, open the route to draw its block boundary and upload an image. Scan coverage is measured against the boundary.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        AdminRouteRequest(
                            code = "",
                            displayName = displayName.trim(),
                            serviceAreaId = 1,
                            payoutAmount = 10000,
                            status = "draft",
                        )
                    )
                },
                enabled = displayName.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = GccGreen),
            ) {
                Text("Create", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignRouteDialog(
    route: Route,
    workers: List<Worker>,
    currentWorkerId: Int?,
    onDismiss: () -> Unit,
    onAssign: (worker: Worker, isTest: Boolean, onResult: (Boolean) -> Unit) -> Unit,
) {
    var selectedWorkerId by remember(route.id, workers, currentWorkerId) {
        mutableStateOf(currentWorkerId ?: workers.firstOrNull()?.id)
    }
    var isTestAssignment by remember(route.id) { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var isAssigning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val selectedWorker = workers.find { it.id == selectedWorkerId }
    val routePayout = route.payoutAmount ?: 0

    AlertDialog(
        onDismissRequest = {
            if (!isAssigning) onDismiss()
        },
        title = {
            Text("Assign Route", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    route.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (workers.isEmpty()) {
                    Text(
                        "Loading workers...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedWorker?.adminWorkerLabel(currentWorkerId) ?: "Select a worker",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Worker") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GccSky),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            workers.forEach { worker ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                worker.adminWorkerLabel(currentWorkerId),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                "${worker.email} \u2022 ${worker.address.zip}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSubtle,
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedWorkerId = worker.id
                                        expanded = false
                                        errorMessage = null
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssignmentModeChip(
                        selected = isTestAssignment,
                        label = "Test",
                        color = GccSky,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            isTestAssignment = true
                            errorMessage = null
                        },
                    )
                    AssignmentModeChip(
                        selected = !isTestAssignment,
                        label = "Real",
                        color = GccGreen,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            isTestAssignment = false
                            errorMessage = null
                        },
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isTestAssignment) GccSky.copy(alpha = 0.12f) else GccGreen.copy(alpha = 0.12f),
                ) {
                    Text(
                        if (isTestAssignment) {
                            "Test assignment creates a zero-payout job for app testing."
                        } else {
                            "Real assignment uses the route payout: $${routePayout / 100}."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isTestAssignment) GccSky else GccGreen,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                errorMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = GccError,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val worker = selectedWorker ?: return@TextButton
                    isAssigning = true
                    errorMessage = null
                    onAssign(worker, isTestAssignment) { success ->
                        isAssigning = false
                        if (!success) {
                            errorMessage = "Could not assign this route."
                        }
                    }
                },
                enabled = selectedWorker != null && !isAssigning && (isTestAssignment || routePayout > 0),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isTestAssignment) GccSky else GccGreen,
                ),
            ) {
                if (isAssigning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (isTestAssignment) "Assign Test" else "Assign Real",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAssigning,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AssignmentModeChip(
    selected: Boolean,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        )
    }
}

private fun Route.adminPathCoordinates(): List<Coordinates> =
    routePathCoordinates()

private fun Worker.adminWorkerLabel(currentWorkerId: Int?): String =
    if (id == currentWorkerId) "$name (me)" else name
