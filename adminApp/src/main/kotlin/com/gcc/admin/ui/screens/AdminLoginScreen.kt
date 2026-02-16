package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.AdminLoginViewModel
import org.koin.compose.koinInject

@Composable
fun AdminLoginScreen(onLoginSuccess: () -> Unit) {
    val viewModel = koinInject<AdminLoginViewModel>()
    val email by viewModel.email.collectAsState()
    val password by viewModel.password.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = MaterialTheme.shapes.large,
            shadowElevation = AdminTheme.elevation.overlay,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                Text("GCC Admin", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Sign in to manage operations",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(AdminTheme.spacing.sm))

                OutlinedTextField(
                    value = email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { viewModel.login(onLoginSuccess) },
                    enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Sign In")
                    }
                }
            }
        }
    }
}
