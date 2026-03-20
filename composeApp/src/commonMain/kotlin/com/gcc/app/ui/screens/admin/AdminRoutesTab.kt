package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.AdminRouteRequest
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus

@Composable
internal fun AdminRoutesTab(
    routes: List<Route>,
    onRouteClick: (Int) -> Unit,
    onStatusToggle: (routeId: Int, newStatus: String) -> Unit,
    onCreateRoute: (AdminRouteRequest) -> Unit = {},
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateRouteDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { request ->
                onCreateRoute(request)
                showCreateDialog = false
            },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GccGreen,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text("+ Create Route", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Text(
                "${routes.size} routes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(routes, key = { it.id }) { route ->
            val distMi = route.distanceMeters?.let { m -> val mi = (m / 1609.34 * 10).toInt() / 10.0; "${mi} mi" } ?: "N/A"
            val statusColor = when (route.status) {
                RouteStatus.PUBLISHED -> GccGreen
                RouteStatus.DRAFT -> Color.Gray
                RouteStatus.ARCHIVED -> GccGold
            }
            val nextStatus = when (route.status) {
                RouteStatus.PUBLISHED -> "draft"
                RouteStatus.DRAFT -> "archived"
                RouteStatus.ARCHIVED -> "published"
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRouteClick(route.id) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (route.imageUrl != null) {
                            NetworkImage(
                                url = route.imageUrl!!,
                                contentDescription = route.displayName,
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
                        Text(route.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "$distMi \u2022 ${route.checkpoints.size} checkpoints",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    route.payoutAmount?.let { cents ->
                        Text(
                            "$${cents / 100}" + if (cents % 100 != 0) ".${cents.rem(100).toString().padStart(2, '0')}" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = GccGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Surface(
                        modifier = Modifier.clickable { onStatusToggle(route.id, nextStatus) },
                        shape = RoundedCornerShape(16.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            route.status.name.lowercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateRouteDialog(
    onDismiss: () -> Unit,
    onCreate: (AdminRouteRequest) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var payoutDollars by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Create Route", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    placeholder = { Text("NL-RN11") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("New Route") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = payoutDollars,
                    onValueChange = { payoutDollars = it },
                    label = { Text("Payout Amount ($)") },
                    placeholder = { Text("25.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val payoutCents = ((payoutDollars.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    onCreate(
                        AdminRouteRequest(
                            code = code.trim(),
                            displayName = displayName.trim(),
                            serviceAreaId = 1,
                            payoutAmount = if (payoutCents > 0) payoutCents else null,
                            status = "draft",
                        )
                    )
                },
                enabled = code.isNotBlank() && displayName.isNotBlank(),
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
