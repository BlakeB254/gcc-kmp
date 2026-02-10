package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RegisterViewModel
import org.koin.compose.koinInject

@Composable
fun RegisterScreen(
    onLoginClick: () -> Unit = {},
    onRegisterSuccess: () -> Unit = {},
) {
    val viewModel = koinInject<RegisterViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isRegistered) {
        if (uiState.isRegistered) onRegisterSuccess()
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            GccLogo(size = 80.dp, showGlow = true)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Join GCC",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
            )
            Text(
                "Create your worker account",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
            )

            Spacer(modifier = Modifier.height(32.dp))

            GccCard(elevation = 8.dp) {
                Spacer(modifier = Modifier.height(8.dp))

                GccTextField(
                    value = name,
                    onValueChange = { name = it; viewModel.clearError() },
                    label = "Full Name",
                    leadingIcon = "\uD83D\uDC64",
                )
                Spacer(modifier = Modifier.height(12.dp))

                GccTextField(
                    value = email,
                    onValueChange = { email = it; viewModel.clearError() },
                    label = "Email",
                    leadingIcon = "\u2709",
                )
                Spacer(modifier = Modifier.height(12.dp))

                GccTextField(
                    value = phone,
                    onValueChange = { phone = it; viewModel.clearError() },
                    label = "Phone",
                    leadingIcon = "\uD83D\uDCDE",
                )
                Spacer(modifier = Modifier.height(12.dp))

                GccTextField(
                    value = password,
                    onValueChange = { password = it; viewModel.clearError() },
                    label = "Password (8+ characters)",
                    isPassword = true,
                    leadingIcon = "\uD83D\uDD12",
                )

                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = GccError,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                GccButton(
                    text = "Create Account",
                    onClick = { viewModel.register(name, email, phone, password) },
                    variant = GccButtonVariant.PRIMARY,
                    enabled = name.isNotBlank() && email.isNotBlank() && password.isNotBlank() && !uiState.isLoading,
                    isLoading = uiState.isLoading,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onLoginClick) {
                Text(
                    "Already have an account? Sign In",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
