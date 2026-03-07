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
import org.koin.compose.koinInject

@Composable
fun BiometricGateScreen(
    onSuccess: () -> Unit,
    onFallback: () -> Unit,
    biometricAuth: BiometricAuth = koinInject(),
) {
    var authenticating by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (!biometricAuth.isBiometricAvailable()) {
            onSuccess()
            return@LaunchedEffect
        }

        val result = biometricAuth.authenticate("Unlock GCC")
        if (result) {
            onSuccess()
        } else {
            onFallback()
        }
        authenticating = false
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

            if (authenticating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = GccGreen,
                    strokeWidth = 3.dp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Authenticating...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
