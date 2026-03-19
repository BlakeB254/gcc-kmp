package com.gcc.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.gcc.app.platform.BiometricAuth
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.LoginViewModel
import com.gcc.shared.auth.TokenManager
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    onRegisterClick: () -> Unit = {},
    viewModel: LoginViewModel = koinInject(),
    tokenManager: TokenManager = koinInject(),
    biometricAuth: BiometricAuth = koinInject(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val rememberedEmail = remember {
        val stored = tokenManager.getRememberedEmail()
        // Guard against corrupted stored emails (e.g., password appended by AutoFill)
        if (stored != null && stored.contains("@") && !stored.contains(" ") && stored.length < 100) {
            stored
        } else {
            if (stored != null) tokenManager.clearRememberMe() // Clear corrupted value
            null
        }
    }
    var email by remember { mutableStateOf(rememberedEmail ?: "") }
    var password by remember { mutableStateOf("") }
    var twoFactorCode by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    var logoVisible by remember { mutableStateOf(false) }
    var cardVisible by remember { mutableStateOf(false) }
    var biometricAvailable by remember { mutableStateOf(false) }
    var hasCredentials by remember { mutableStateOf(false) }

    // Reset ViewModel state to prevent stale isLoggedIn from a previous session
    // (LoginViewModel is a Koin singleton — its state survives navigation)
    LaunchedEffect(Unit) {
        viewModel.resetState()
        logoVisible = true
        delay(300)
        cardVisible = true
        biometricAvailable = biometricAuth.isBiometricAvailable()
        hasCredentials = tokenManager.hasBiometricToken() ||
            tokenManager.hasStoredCredentials()
    }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            if (rememberMe && email.isNotBlank()) {
                tokenManager.saveRememberMe(email)
            } else {
                tokenManager.clearRememberMe()
            }
            onLoginSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GccGreen, GccGreenDark, Color(0xFF0F4C2A))
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.15f))

            // Logo with fade-in
            AnimatedVisibility(
                visible = logoVisible,
                enter = fadeIn(animationSpec = tween(600)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GccLogo(size = 96.dp, showGlow = true)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Global Community Cleaners",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Sign in to get started",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Card slides up
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(animationSpec = tween(400)) +
                    slideInVertically(
                        animationSpec = tween(400),
                        initialOffsetY = { it / 4 },
                    ),
            ) {
                GccCard(elevation = 8.dp) {
                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.requiresTwoFactor) {
                        // 2FA verification screen
                        Text(
                            "Check your email",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "We sent a 6-digit verification code to your email address.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        GccTextField(
                            value = twoFactorCode,
                            onValueChange = { input ->
                                if (input.length <= 6 && input.all { it.isDigit() }) {
                                    twoFactorCode = input
                                    viewModel.clearError()
                                }
                            },
                            label = "Verification Code",
                            leadingIcon = "#",
                            enabled = !uiState.isLoading,
                        )

                        if (uiState.error != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        GccButton(
                            text = "Verify",
                            onClick = { viewModel.verify2FA(twoFactorCode) },
                            variant = GccButtonVariant.PRIMARY,
                            isLoading = uiState.isLoading,
                            enabled = twoFactorCode.length == 6,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(onClick = {
                            twoFactorCode = ""
                            viewModel.login(email, password)
                        }) {
                            Text(
                                "Resend Code",
                                color = GccGreen,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        TextButton(onClick = {
                            twoFactorCode = ""
                            viewModel.cancel2FA()
                        }) {
                            Text(
                                "Back to Sign In",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        // Normal login form
                        GccTextField(
                            value = email,
                            onValueChange = { email = it; viewModel.clearError() },
                            label = "Email",
                            isEmail = true,
                            leadingIcon = "\u2709",
                            enabled = !uiState.isLoading,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        GccTextField(
                            value = password,
                            onValueChange = { password = it; viewModel.clearError() },
                            label = "Password",
                            isPassword = true,
                            leadingIcon = "\uD83D\uDD12",
                            enabled = !uiState.isLoading,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = rememberMe,
                                onCheckedChange = { rememberMe = it },
                                colors = CheckboxDefaults.colors(checkedColor = GccGreen),
                            )
                            Text(
                                "Remember me on this device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (uiState.error != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        GccButton(
                            text = "Sign In",
                            onClick = { viewModel.login(email, password) },
                            variant = GccButtonVariant.PRIMARY,
                            isLoading = uiState.isLoading,
                            enabled = email.isNotBlank() && password.isNotBlank(),
                        )

                        // Face ID / biometric quick sign-in
                        Spacer(modifier = Modifier.height(12.dp))

                        val scope = rememberCoroutineScope()
                        if (biometricAvailable && hasCredentials) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val success = biometricAuth.authenticate("Sign in to GCC")
                                        if (success) {
                                            viewModel.loginWithBiometric(onSuccess = onLoginSuccess)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = GccGreen,
                                ),
                            ) {
                                Text(
                                    "\uD83D\uDD13  Sign in with Face ID",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        } else if (biometricAvailable) {
                            Text(
                                "\uD83D\uDD13 Face ID available after first sign-in",
                                style = MaterialTheme.typography.bodySmall,
                                color = GccGreen,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Register link
            TextButton(onClick = onRegisterClick) {
                Text(
                    "New here? Register",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Version
            Text(
                "v1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
