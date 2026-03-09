package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.BiometricAuth
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.viewmodel.LoginViewModel
import org.koin.compose.koinInject

/**
 * Biometric gate shown on cold launch when the user has a previous session.
 *
 * Flow (single sequential LaunchedEffect, no race conditions):
 * 1. Reset stale ViewModel state
 * 2. Prompt Face ID (if available)
 * 3. If Face ID passes → attempt silent re-login via token or saved credentials
 * 4. Navigate on result: success → Dashboard, failure → Login
 *
 * All navigation happens inside the LaunchedEffect — no separate watchers needed.
 */
@Composable
fun BiometricGateScreen(
    onSuccess: () -> Unit,
    onFallback: () -> Unit,
    biometricAuth: BiometricAuth = koinInject(),
    loginViewModel: LoginViewModel = koinInject(),
) {
    var statusText by remember { mutableStateOf("Authenticating...") }
    var navigated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loginViewModel.resetState()

        // Give iOS time to fully present the window — LAContext requires foreground
        kotlinx.coroutines.delay(600)

        // Step 1: Prompt Face ID if available
        val hasBiometric = biometricAuth.isBiometricAvailable()
        if (hasBiometric) {
            val result = biometricAuth.authenticate("Unlock GCC")
            if (!result) {
                navigated = true
                onFallback()
                return@LaunchedEffect
            }
        }

        // Step 2: Face ID passed (or not available) — try to restore session
        statusText = "Signing in..."
        val loggedIn = loginViewModel.loginWithBiometricSuspend()

        // Step 3: Navigate based on result
        if (!navigated) {
            navigated = true
            if (loggedIn) onSuccess() else onFallback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E0A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GccLogo(size = 80.dp, showGlow = true)

            Spacer(modifier = Modifier.height(32.dp))

            if (!navigated) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = GccGreen,
                    strokeWidth = 3.dp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
