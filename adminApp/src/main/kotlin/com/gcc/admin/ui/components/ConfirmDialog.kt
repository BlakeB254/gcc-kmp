package com.gcc.admin.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.gcc.admin.ui.theme.AdminError

/**
 * Confirmation dialog for destructive or significant actions.
 *
 * The confirm button is styled with the error color to reinforce that
 * the action has consequences.
 *
 * @param title Dialog title (e.g. "Suspend Worker").
 * @param message Descriptive body text explaining the action.
 * @param confirmText Label for the confirm button (e.g. "Suspend", "Delete").
 * @param onConfirm Callback invoked when the user confirms.
 * @param onDismiss Callback invoked when the user cancels or taps outside.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AdminError,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
