package com.gcc.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
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
        if (stored != null && stored.contains("@") && !stored.contains(" ") && stored.length < 100) {
            stored
        } else {
            if (stored != null) tokenManager.clearRememberMe()
            null
        }
    }
    var email by remember { mutableStateOf(rememberedEmail ?: "") }
    var password by remember { mutableStateOf("") }
    var twoFactorCode by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    var contentVisible by remember { mutableStateOf(false) }
    var biometricAvailable by remember { mutableStateOf(false) }
    var hasCredentials by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(Unit) {
        viewModel.resetState()
        delay(200)
        contentVisible = true
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
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { dismissKeyboard() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo header
            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500)),
            ) {
                GccLogo(
                    size = 280.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Main content area
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) +
                    slideInVertically(tween(400, delayMillis = 200)) { it / 6 },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (uiState.requiresTwoFactor) {
                        TwoFactorSection(
                            code = twoFactorCode,
                            onCodeChange = { input ->
                                if (input.length <= 6 && input.all { it.isDigit() }) {
                                    twoFactorCode = input
                                    viewModel.clearError()
                                }
                            },
                            error = uiState.error,
                            isLoading = uiState.isLoading,
                            onVerify = { viewModel.verify2FA(twoFactorCode) },
                            onResend = { twoFactorCode = ""; viewModel.login(email, password) },
                            onCancel = { twoFactorCode = ""; viewModel.cancel2FA() },
                        )
                    } else {
                        // Social sign-in buttons first (prominent)
                        if (viewModel.canUseAppleSignIn) {
                            Button(
                                onClick = { viewModel.loginWithApple() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                enabled = !uiState.isLoading,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    "\uF8FF  Sign in with Apple",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (viewModel.canUseGoogleSignIn) {
                            OutlinedButton(
                                onClick = { viewModel.loginWithGoogle() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                enabled = !uiState.isLoading,
                                shape = RoundedCornerShape(12.dp),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            ) {
                                Text(
                                    "G  Continue with Google",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        // Face ID quick sign-in
                        val scope = rememberCoroutineScope()
                        if (biometricAvailable && hasCredentials) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val success = biometricAuth.authenticate("Sign in to GCC")
                                        if (success) {
                                            viewModel.loginWithBiometric(onSuccess = onLoginSuccess)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            ) {
                                Text(
                                    "\uD83D\uDD13  Sign in with Face ID",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }

                        // Divider
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Text(
                                "  or sign in with email  ",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))

                        // Email + password form
                        GccTextField(
                            value = email,
                            onValueChange = { email = it; viewModel.clearError() },
                            label = "Email address",
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
                                "Remember me",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }

                        if (uiState.error != null) {
                            Text(
                                text = uiState.error!!,
                                color = GccError,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        GccButton(
                            text = "Sign In",
                            onClick = { viewModel.login(email, password) },
                            variant = GccButtonVariant.PRIMARY,
                            isLoading = uiState.isLoading,
                            enabled = email.isNotBlank() && password.isNotBlank(),
                        )

                        if (biometricAvailable && !hasCredentials) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "\uD83D\uDD13 Face ID available after first sign-in",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtle,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Register link with green pill background
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(400, delayMillis = 400)),
            ) {
                TextButton(
                    onClick = onRegisterClick,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(GccGreen.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        "Don't have an account? ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                    Text(
                        "Sign Up",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = GccGreen,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Version
            Text(
                "v1.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TwoFactorSection(
    code: String,
    onCodeChange: (String) -> Unit,
    error: String?,
    isLoading: Boolean,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Verify Your Identity",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "We sent a 6-digit code to your email.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))

            GccTextField(
                value = code,
                onValueChange = onCodeChange,
                label = "Verification Code",
                leadingIcon = "#",
                enabled = !isLoading,
            )

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = GccError, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(20.dp))

            GccButton(
                text = "Verify",
                onClick = onVerify,
                variant = GccButtonVariant.PRIMARY,
                isLoading = isLoading,
                enabled = code.length == 6,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onResend) {
                    Text("Resend Code", color = GccGreen, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCancel) {
                    Text("Back to Sign In", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
