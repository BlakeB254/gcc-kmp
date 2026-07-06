package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.openUrl
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AccountSettingsStep
import com.gcc.app.viewmodel.AccountSettingsViewModel
import org.koin.compose.koinInject

/**
 * Account settings hub: edit name, change email (verified), change phone,
 * change password, and manage two-factor authentication. One nav entry;
 * sub-flows are steps driven by [AccountSettingsViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<AccountSettingsViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    val onRecoveryCodesScreen = uiState.step is AccountSettingsStep.TwoFactorRecoveryCodes
    val handleBack: () -> Unit = {
        when {
            // Recovery codes must be explicitly acknowledged, not backed out of.
            onRecoveryCodesScreen -> {}
            uiState.step is AccountSettingsStep.Overview -> onBack()
            else -> viewModel.backToOverview()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stepTitle(uiState.step)) },
                navigationIcon = {
                    if (!onRecoveryCodesScreen) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGreen,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
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
            when (uiState.step) {
                is AccountSettingsStep.Overview -> OverviewStep(viewModel)
                is AccountSettingsStep.EditName -> EditNameStep(viewModel)
                is AccountSettingsStep.ChangeEmail -> ChangeEmailStep(viewModel)
                is AccountSettingsStep.ConfirmEmail -> ConfirmEmailStep(viewModel)
                is AccountSettingsStep.ChangePhone -> ChangePhoneStep(viewModel)
                is AccountSettingsStep.ChangePassword -> ChangePasswordStep(viewModel)
                is AccountSettingsStep.TwoFactorSetup -> TwoFactorSetupStep(viewModel)
                is AccountSettingsStep.TwoFactorConfirm -> TwoFactorConfirmStep(viewModel)
                is AccountSettingsStep.TwoFactorRecoveryCodes -> RecoveryCodesStep(viewModel)
                is AccountSettingsStep.TwoFactorDisable -> TwoFactorDisableStep(viewModel)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun stepTitle(step: AccountSettingsStep): String = when (step) {
    is AccountSettingsStep.Overview -> "Account"
    is AccountSettingsStep.EditName -> "Edit Name"
    is AccountSettingsStep.ChangeEmail, is AccountSettingsStep.ConfirmEmail -> "Change Email"
    is AccountSettingsStep.ChangePhone -> "Change Phone"
    is AccountSettingsStep.ChangePassword -> "Change Password"
    is AccountSettingsStep.TwoFactorSetup,
    is AccountSettingsStep.TwoFactorConfirm,
    is AccountSettingsStep.TwoFactorRecoveryCodes -> "Two-Factor Authentication"
    is AccountSettingsStep.TwoFactorDisable -> "Turn Off 2FA"
}

@Composable
private fun ErrorText(error: String?) {
    error?.let { Text(it, color = GccError, style = MaterialTheme.typography.bodySmall) }
}

// ── Overview ───────────────────────────────────────────────────────────

@Composable
private fun OverviewStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val worker = uiState.worker

    uiState.successMessage?.let { message ->
        Surface(
            color = GccGreen.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "✓ $message",
                color = GccGreen,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    ErrorText(uiState.error)

    if (uiState.isLoading && worker == null) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = GccGreen,
            strokeWidth = 2.dp,
        )
        return
    }
    if (worker == null) return

    SectionHeader(title = "Profile")
    OverviewRow("Name", worker.name) { viewModel.goTo(AccountSettingsStep.EditName) }
    OverviewRow(
        label = "Email",
        value = worker.email,
        badge = if (worker.emailVerified) "Verified" else null,
    ) { viewModel.goTo(AccountSettingsStep.ChangeEmail) }
    OverviewRow("Phone", worker.phone.ifBlank { "Not set" }) {
        viewModel.goTo(AccountSettingsStep.ChangePhone)
    }

    SectionHeader(title = "Security")
    OverviewRow("Password", "••••••••") { viewModel.goTo(AccountSettingsStep.ChangePassword) }
    OverviewRow(
        label = "Two-Factor Authentication",
        value = if (worker.twoFactorEnabled) "On" else "Off",
        badge = if (worker.twoFactorEnabled) "Recommended ✓" else null,
    ) {
        viewModel.goTo(
            if (worker.twoFactorEnabled) AccountSettingsStep.TwoFactorDisable
            else AccountSettingsStep.TwoFactorSetup,
        )
    }
    if (!worker.twoFactorEnabled) {
        Text(
            "Add an extra layer of security: after entering your password, " +
                "you'll also enter a code from an authenticator app when signing in.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun OverviewRow(
    label: String,
    value: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    GccCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                    badge?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = GccGreen)
                    }
                }
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = TextSubtle)
        }
    }
}

// ── Simple field steps ─────────────────────────────────────────────────

@Composable
private fun EditNameStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var name by remember { mutableStateOf(uiState.worker?.name ?: "") }

    GccTextField(
        value = name,
        onValueChange = { name = it; viewModel.clearError() },
        label = "Full name",
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Save",
        onClick = { viewModel.submitName(name) },
        isLoading = uiState.isSubmitting,
        enabled = name.isNotBlank() && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChangeEmailStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var newEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Text(
        "We'll send a 6-digit code to the new address. Your email only changes " +
            "after you enter that code.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
    GccTextField(
        value = newEmail,
        onValueChange = { newEmail = it; viewModel.clearError() },
        label = "New email address",
        isEmail = true,
        modifier = Modifier.fillMaxWidth(),
    )
    GccTextField(
        value = password,
        onValueChange = { password = it; viewModel.clearError() },
        label = "Current password",
        isPassword = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Send Code",
        onClick = { viewModel.submitEmailChange(newEmail, password) },
        isLoading = uiState.isSubmitting,
        enabled = newEmail.isNotBlank() && password.isNotBlank() && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmEmailStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var code by remember { mutableStateOf("") }

    Text(
        "Enter the 6-digit code we sent to ${uiState.pendingEmail ?: "your new address"}. " +
            "The code expires in 30 minutes.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
    GccTextField(
        value = code,
        onValueChange = { input ->
            if (input.length <= 6 && input.all { it.isDigit() }) {
                code = input
                viewModel.clearError()
            }
        },
        label = "Verification code",
        leadingIcon = "#",
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Confirm New Email",
        onClick = { viewModel.confirmEmailCode(code) },
        isLoading = uiState.isSubmitting,
        enabled = code.length == 6 && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        onClick = { viewModel.goTo(AccountSettingsStep.ChangeEmail) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Didn't get it? Start over", color = TextMuted)
    }
}

@Composable
private fun ChangePhoneStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var newPhone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    uiState.worker?.phone?.takeIf { it.isNotBlank() }?.let {
        Text("Current number: $it", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
    }
    GccTextField(
        value = newPhone,
        onValueChange = { newPhone = it; viewModel.clearError() },
        label = "New phone number",
        isPhone = true,
        modifier = Modifier.fillMaxWidth(),
    )
    GccTextField(
        value = password,
        onValueChange = { password = it; viewModel.clearError() },
        label = "Current password",
        isPassword = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Update Phone Number",
        onClick = { viewModel.submitPhoneChange(newPhone, password) },
        isLoading = uiState.isSubmitting,
        enabled = newPhone.isNotBlank() && password.isNotBlank() && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChangePasswordStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    GccTextField(
        value = current,
        onValueChange = { current = it; viewModel.clearError() },
        label = "Current password",
        isPassword = true,
        modifier = Modifier.fillMaxWidth(),
    )
    GccTextField(
        value = new,
        onValueChange = { new = it; viewModel.clearError() },
        label = "New password (min 8 characters)",
        isPassword = true,
        modifier = Modifier.fillMaxWidth(),
    )
    GccTextField(
        value = confirm,
        onValueChange = { confirm = it; viewModel.clearError() },
        label = "Confirm new password",
        isPassword = true,
        error = if (confirm.isNotEmpty() && confirm != new) "Passwords don't match" else null,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Change Password",
        onClick = { viewModel.submitPasswordChange(current, new, confirm) },
        isLoading = uiState.isSubmitting,
        enabled = current.isNotBlank() && new.length >= 8 && confirm == new && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Two-factor steps ───────────────────────────────────────────────────

@Composable
private fun TwoFactorSetupStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var password by remember { mutableStateOf("") }

    Text(
        "Two-factor authentication protects your account even if someone learns " +
            "your password. You'll need an authenticator app (like Google " +
            "Authenticator, Authy, or the built-in iOS Passwords app).",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
    GccTextField(
        value = password,
        onValueChange = { password = it; viewModel.clearError() },
        label = "Confirm your password",
        isPassword = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Continue",
        onClick = { viewModel.startTwoFactorSetup(password) },
        isLoading = uiState.isSubmitting,
        enabled = password.isNotBlank() && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TwoFactorConfirmStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }

    Text(
        "1. Add your GCC account to your authenticator app",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
    )
    uiState.otpauthUri?.let { uri ->
        GccButton(
            text = "Open Authenticator App",
            onClick = { openUrl(uri) },
            variant = GccButtonVariant.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        "Or enter this setup key manually:",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )
    uiState.totpSecret?.let { secret ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    secret.chunked(4).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(secret)) }) {
                    Text("Copy setup key", color = GccGreen)
                }
            }
        }
    }

    Text(
        "2. Enter the 6-digit code the app shows",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
    )
    GccTextField(
        value = code,
        onValueChange = { input ->
            if (input.length <= 6 && input.all { it.isDigit() }) {
                code = input
                viewModel.clearError()
            }
        },
        label = "Authenticator code",
        leadingIcon = "#",
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Turn On 2FA",
        onClick = { viewModel.confirmTwoFactorCode(code) },
        isLoading = uiState.isSubmitting,
        enabled = code.length == 6 && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecoveryCodesStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current

    Text(
        "Save your recovery codes",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
    Text(
        "If you lose access to your authenticator app, a recovery code is the " +
            "only way back into your account. Each code works once. " +
            "This is the only time they will be shown.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            uiState.recoveryCodes.forEach { recoveryCode ->
                Text(
                    recoveryCode,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
    GccButton(
        text = "Copy All Codes",
        onClick = { clipboard.setText(AnnotatedString(uiState.recoveryCodes.joinToString("\n"))) },
        variant = GccButtonVariant.SECONDARY,
        modifier = Modifier.fillMaxWidth(),
    )
    GccButton(
        text = "I Saved My Recovery Codes",
        onClick = { viewModel.acknowledgeRecoveryCodes() },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TwoFactorDisableStep(viewModel: AccountSettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Text(
        "Turning off two-factor authentication makes your account less secure. " +
            "Confirm with your password and a code from your authenticator app " +
            "(or a recovery code).",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
    GccTextField(
        value = password,
        onValueChange = { password = it; viewModel.clearError() },
        label = "Password",
        isPassword = true,
        modifier = Modifier.fillMaxWidth(),
    )
    GccTextField(
        value = code,
        onValueChange = { code = it; viewModel.clearError() },
        label = "Authenticator or recovery code",
        leadingIcon = "#",
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(uiState.error)
    GccButton(
        text = "Turn Off 2FA",
        onClick = { viewModel.disableTwoFactor(password, code) },
        isLoading = uiState.isSubmitting,
        enabled = password.isNotBlank() && code.isNotBlank() && !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    )
}
