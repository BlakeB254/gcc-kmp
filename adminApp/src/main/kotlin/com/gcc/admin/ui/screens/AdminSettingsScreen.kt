package com.gcc.admin.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.*
import com.gcc.shared.api.GccApiClient
import org.koin.compose.koinInject

@Composable
fun AdminSettingsScreen() {
    val api = koinInject<GccApiClient>()

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // ── Server Configuration ────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                Text("Server Configuration", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = api.baseUrl,
                    onValueChange = {},
                    label = { Text("API Base URL") },
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── App Mode ────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                Text("Application Mode", style = MaterialTheme.typography.titleMedium)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = AdminInfo,
                        modifier = Modifier.size(20.dp),
                    )
                    val isDemoMode = api.baseUrl.contains("demo") || api.baseUrl.contains("mock")
                    Text(
                        text = if (isDemoMode) "Demo Mode" else "Production Mode",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ── Account ─────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                Text("Account", style = MaterialTheme.typography.titleMedium)

                OutlinedButton(
                    onClick = { /* Sign out handled by navigation */ },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AdminError),
                    border = BorderStroke(1.dp, AdminError),
                ) {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(AdminTheme.spacing.sm))
                    Text("Sign Out")
                }
            }
        }

        // ── App Info ────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text("GCC Admin Desktop", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Version 1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant,
                )
                Text(
                    "Gotham City Cleanup - Admin Operations Console",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}
