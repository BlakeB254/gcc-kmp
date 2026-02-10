package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.SettingsViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToLegal: (String) -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val viewModel = koinInject<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadSettings() }

    LaunchedEffect(uiState.signedOut) {
        if (uiState.signedOut) onSignOut()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGreen,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Account Section
            SectionHeader(title = "Account")
            GccCard {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = GccGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    uiState.worker?.let { worker ->
                        Text(
                            worker.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            worker.email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                        worker.phone.takeIf { it.isNotBlank() }?.let { phone ->
                            Text(
                                phone,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSubtle,
                            )
                        }
                    }
                }
            }

            // Notifications Section
            SectionHeader(title = "Notifications")
            GccCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Push Notifications",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Route alerts, payment updates",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    Switch(
                        checked = uiState.pushNotificationsEnabled,
                        onCheckedChange = { viewModel.togglePushNotifications(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = GccGreen),
                    )
                }
            }

            // Legal Section
            SectionHeader(title = "Legal")
            GccCard(onClick = { onNavigateToLegal("terms") }) {
                Text("Terms of Service", style = MaterialTheme.typography.bodyLarge)
            }
            GccCard(onClick = { onNavigateToLegal("privacy") }) {
                Text("Privacy Policy", style = MaterialTheme.typography.bodyLarge)
            }
            GccCard(onClick = { onNavigateToLegal("about") }) {
                Text("About", style = MaterialTheme.typography.bodyLarge)
            }

            // Support Section
            SectionHeader(title = "Support")
            GccCard(onClick = { onNavigateToLegal("help") }) {
                Text("Help & FAQ", style = MaterialTheme.typography.bodyLarge)
            }

            // Danger Zone
            Spacer(modifier = Modifier.height(16.dp))
            GccButton(
                text = "Sign Out",
                onClick = { viewModel.signOut() },
                variant = GccButtonVariant.OUTLINED,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
