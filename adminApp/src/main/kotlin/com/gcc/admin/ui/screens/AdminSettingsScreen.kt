package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.viewmodel.AdminLoginViewModel
import org.koin.compose.koinInject

/**
 * Admin settings screen with API configuration display,
 * admin info, and logout functionality.
 */
@Composable
fun AdminSettingsScreen(
    loginViewModel: AdminLoginViewModel = koinInject(),
) {
    val loginState by loginViewModel.state.collectAsState()

    AdminScaffold(
        title = "Settings",
        subtitle = "System",
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            // API Configuration
            DetailPanel(
                title = "API Configuration",
                items = listOf(
                    "API URL" to "http://localhost:7310",
                    "Connection" to "Local Development",
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Admin Info
            DetailPanel(
                title = "Admin Session",
                items = listOf(
                    "Email" to if (loginState.email.isNotBlank()) loginState.email else "admin@gcc.app",
                    "Status" to if (loginState.isLoggedIn) "Authenticated" else "Not Authenticated",
                    "Role" to "Super Admin",
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Application Info
            DetailPanel(
                title = "Application",
                items = listOf(
                    "Version" to "1.0.0",
                    "Platform" to "Desktop (JVM)",
                    "Build" to "Development",
                ),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Logout button
            Button(
                onClick = { loginViewModel.logout() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out")
            }
        }
    }
}
