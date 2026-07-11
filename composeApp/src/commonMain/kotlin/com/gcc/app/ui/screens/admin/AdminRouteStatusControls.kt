package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.StatusChip
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccSky
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus

@Composable
internal fun AdminRouteStatusControls(
    route: Route,
    isUpdating: Boolean,
    actionError: String?,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onStatusAction: (AdminRouteStatusAction, (Boolean, String?) -> Unit) -> Unit,
) {
    val presentation = route.status.presentation()
    var pending by remember(route.id) { mutableStateOf<AdminRouteStatusAction?>(null) }
    var dialogError by remember(route.id, pending) { mutableStateOf<String?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteStatusBadge(route.status)
            Spacer(Modifier.width(8.dp))
            Text(
                text = presentation.helperText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val primary = presentation.primaryAction
            val primaryModifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = primary.accessibilityLabel(route.displayName)
                }
            if (primary == AdminRouteStatusAction.PUBLISH) {
                Button(
                    onClick = { pending = primary },
                    enabled = !isUpdating,
                    modifier = primaryModifier,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GccGreen,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(primary.label)
                }
            } else {
                OutlinedButton(
                    onClick = { pending = primary },
                    enabled = !isUpdating,
                    modifier = primaryModifier,
                ) {
                    Text(primary.label)
                }
            }
            presentation.secondaryAction?.let { secondary ->
                TextButton(
                    onClick = { pending = secondary },
                    enabled = !isUpdating,
                    modifier = Modifier.semantics {
                        contentDescription = secondary.accessibilityLabel(route.displayName)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(secondary.label)
                }
            }
        }
        if (expanded && isUpdating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    pending?.let { action ->
        ConfirmRouteStatusDialog(
            route = route,
            action = action,
            isUpdating = isUpdating,
            errorMessage = dialogError ?: actionError,
            onDismiss = { if (!isUpdating) pending = null },
            onConfirm = {
                dialogError = null
                onStatusAction(action) { success, error ->
                    if (success) pending = null else dialogError = error
                }
            },
        )
    }
}

internal fun RouteStatus.statusAccentColor(): Color = when (this) {
    RouteStatus.DRAFT -> Color.Gray
    RouteStatus.PUBLISHED -> GccGreen
    RouteStatus.ARCHIVED -> GccSky
}

@Composable
private fun RouteStatusBadge(status: RouteStatus) {
    val presentation = status.presentation()
    val color = status.statusAccentColor()
    StatusChip(
        text = presentation.label,
        dotColor = color,
        backgroundColor = color.copy(alpha = 0.15f),
    )
}

@Composable
private fun ConfirmRouteStatusDialog(
    route: Route,
    action: AdminRouteStatusAction,
    isUpdating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        title = { Text(action.label, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(action.confirmationCopy(route.displayName))
                errorMessage?.let {
                    Text(
                        text = it,
                        color = GccError,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isUpdating,
                modifier = Modifier.semantics {
                    contentDescription = action.accessibilityLabel(route.displayName)
                },
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(action.label, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUpdating) {
                Text("Cancel")
            }
        },
    )
}
