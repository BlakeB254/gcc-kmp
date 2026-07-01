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
import com.gcc.app.platform.BiometricAuth
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.ProfileViewModel
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
    val profileVm = koinInject<ProfileViewModel>()
    val biometricAuth = koinInject<BiometricAuth>()
    val biometricAvailable = remember { biometricAuth.isBiometricAvailable() }
    val uiState by viewModel.uiState.collectAsState()
    val profileState by profileVm.uiState.collectAsState()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadSettings() }
    LaunchedEffect(Unit) { profileVm.loadProfile() }
    LaunchedEffect(Unit) { viewModel.loadConnectStatus() }

    LaunchedEffect(uiState.signedOut) {
        if (uiState.signedOut) onSignOut()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
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
        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text("Delete Account") },
                text = {
                    Text(
                        "Are you sure you want to permanently delete your account? " +
                            "This will remove all your data including cleaning history, " +
                            "earnings records, and profile information. This action cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            viewModel.deleteAccount()
                        },
                    ) {
                        Text("Delete", color = GccError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

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

            // Display Handle Section
            HorizontalDivider(color = DividerColor)

            Text(
                "Display Handle",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                "Shown on your shareable card instead of your real name",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            var handleInput by remember(profileState.worker?.displayHandle) {
                mutableStateOf(profileState.worker?.displayHandle ?: "")
            }
            GccTextField(
                value = handleInput,
                onValueChange = { newVal ->
                    handleInput = newVal.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }
                },
                label = "@handle",
                modifier = Modifier.fillMaxWidth(),
            )
            if (handleInput.isNotEmpty() && handleInput != (profileState.worker?.displayHandle ?: "")) {
                GccButton(
                    text = if (profileState.handleUpdateLoading) "Saving..." else "Save Handle",
                    onClick = { profileVm.updateDisplayHandle(handleInput) },
                    variant = GccButtonVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            profileState.handleUpdateError?.let { error ->
                Text(error, color = GccError, style = MaterialTheme.typography.bodySmall)
            }

            // Biometric Lock Section (only when available)
            if (biometricAvailable) {
                SectionHeader(title = "Security")
                GccCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Face ID / Biometric Lock",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "Require biometric to open app",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = uiState.biometricEnabled,
                            onCheckedChange = { viewModel.toggleBiometric(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = GccGreen),
                        )
                    }
                }
            }

            // Payouts Section
            SectionHeader(title = "Payouts")
            val connectStatus = uiState.connectStatus
            val payoutsReady = connectStatus?.payoutsReady == true
            val payoutsLabel = when {
                payoutsReady -> "Payouts ready ✓"
                connectStatus != null && connectStatus.onboardingStatus != "not_started" -> "Finish setup"
                else -> "Set up payouts"
            }
            GccCard(
                onClick = if (!payoutsReady && !uiState.connectOnboardLoading) {
                    { viewModel.startConnectOnboard() }
                } else null,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            payoutsLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (payoutsReady) GccGreen else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!payoutsReady) {
                            Text(
                                "Enable same-day payouts",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                    if (uiState.connectStatusLoading || uiState.connectOnboardLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = GccGreen,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
            uiState.connectError?.let { error ->
                Text(error, color = GccError, style = MaterialTheme.typography.bodySmall)
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

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showDeleteConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (uiState.isDeletingAccount) "Deleting Account..." else "Delete Account",
                    color = GccError,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
