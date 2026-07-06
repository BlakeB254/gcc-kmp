package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.AccountValidation
import com.gcc.shared.model.Worker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which sub-flow of the Account screen is on screen. */
sealed class AccountSettingsStep {
    data object Overview : AccountSettingsStep()
    data object EditName : AccountSettingsStep()
    data object ChangeEmail : AccountSettingsStep()

    /** Code sent to the new address; waiting for the user to type it. */
    data object ConfirmEmail : AccountSettingsStep()
    data object ChangePhone : AccountSettingsStep()
    data object ChangePassword : AccountSettingsStep()

    /** Password re-auth before a TOTP secret is issued. */
    data object TwoFactorSetup : AccountSettingsStep()

    /** Secret issued; waiting for a live authenticator code. */
    data object TwoFactorConfirm : AccountSettingsStep()

    /** 2FA just enabled; recovery codes shown exactly once. */
    data object TwoFactorRecoveryCodes : AccountSettingsStep()
    data object TwoFactorDisable : AccountSettingsStep()
}

data class AccountSettingsUiState(
    val worker: Worker? = null,
    val step: AccountSettingsStep = AccountSettingsStep.Overview,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** One-shot confirmation shown on the Overview after a completed flow. */
    val successMessage: String? = null,
    val pendingEmail: String? = null,
    val totpSecret: String? = null,
    val otpauthUri: String? = null,
    val recoveryCodes: List<String> = emptyList(),
)

class AccountSettingsViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(AccountSettingsUiState())
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    fun load() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val worker = apiClient.getWorkerProfile()
                _uiState.value = _uiState.value.copy(worker = worker, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load account",
                )
            }
        }
    }

    fun goTo(step: AccountSettingsStep) {
        _uiState.value = _uiState.value.copy(step = step, error = null, successMessage = null)
    }

    fun backToOverview() {
        // Dropping recovery codes/secret from state on exit is deliberate —
        // they should not survive navigation.
        _uiState.value = _uiState.value.copy(
            step = AccountSettingsStep.Overview,
            error = null,
            isSubmitting = false,
            pendingEmail = null,
            totpSecret = null,
            otpauthUri = null,
            recoveryCodes = emptyList(),
        )
    }

    fun clearError() {
        if (_uiState.value.error != null) {
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

    private fun fail(e: Exception, fallback: String) {
        _uiState.value = _uiState.value.copy(isSubmitting = false, error = e.message ?: fallback)
    }

    /** Refresh the profile and land on the Overview with a success note. */
    private suspend fun finishFlow(message: String) {
        val worker = try {
            apiClient.getWorkerProfile()
        } catch (_: Exception) {
            _uiState.value.worker
        }
        _uiState.value = _uiState.value.copy(
            worker = worker,
            step = AccountSettingsStep.Overview,
            isSubmitting = false,
            error = null,
            successMessage = message,
            pendingEmail = null,
            totpSecret = null,
            otpauthUri = null,
            recoveryCodes = emptyList(),
        )
    }

    fun submitName(name: String) {
        AccountValidation.validateName(name)?.let {
            _uiState.value = _uiState.value.copy(error = it); return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                apiClient.updateProfileName(name.trim())
                finishFlow("Name updated")
            } catch (e: Exception) {
                fail(e, "Couldn't update name")
            }
        }
    }

    fun submitEmailChange(newEmail: String, password: String) {
        AccountValidation.validateEmail(newEmail)?.let {
            _uiState.value = _uiState.value.copy(error = it); return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                val started = apiClient.requestEmailChange(
                    AccountValidation.normalizeEmail(newEmail),
                    password,
                )
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    pendingEmail = started.pendingEmail,
                    step = AccountSettingsStep.ConfirmEmail,
                )
            } catch (e: Exception) {
                fail(e, "Couldn't start email change")
            }
        }
    }

    fun confirmEmailCode(code: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                apiClient.confirmEmailChange(code.filterNot { it.isWhitespace() })
                finishFlow("Email updated")
            } catch (e: Exception) {
                fail(e, "Couldn't confirm email")
            }
        }
    }

    fun submitPhoneChange(newPhone: String, password: String) {
        AccountValidation.validatePhone(newPhone)?.let {
            _uiState.value = _uiState.value.copy(error = it); return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                apiClient.changePhone(newPhone.trim(), password)
                finishFlow("Phone number updated")
            } catch (e: Exception) {
                fail(e, "Couldn't update phone number")
            }
        }
    }

    fun submitPasswordChange(current: String, new: String, confirm: String) {
        AccountValidation.validateNewPassword(new, confirm)?.let {
            _uiState.value = _uiState.value.copy(error = it); return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                apiClient.changePassword(current, new)
                finishFlow("Password updated")
            } catch (e: Exception) {
                fail(e, "Couldn't change password")
            }
        }
    }

    fun startTwoFactorSetup(password: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                val setup = apiClient.setupTwoFactor(password)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    totpSecret = setup.secret,
                    otpauthUri = setup.otpauthUri,
                    step = AccountSettingsStep.TwoFactorConfirm,
                )
            } catch (e: Exception) {
                fail(e, "Couldn't start 2FA setup")
            }
        }
    }

    fun confirmTwoFactorCode(code: String) {
        if (!AccountValidation.isTotpCode(code)) {
            _uiState.value = _uiState.value.copy(error = "Enter the 6-digit code from your authenticator app")
            return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                val enabled = apiClient.enableTwoFactor(code.filterNot { it.isWhitespace() })
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    totpSecret = null,
                    otpauthUri = null,
                    recoveryCodes = enabled.recoveryCodes,
                    step = AccountSettingsStep.TwoFactorRecoveryCodes,
                )
            } catch (e: Exception) {
                fail(e, "Couldn't verify the code")
            }
        }
    }

    /** "I saved my recovery codes" — the only way off the codes screen. */
    fun acknowledgeRecoveryCodes() {
        scope.launch { finishFlow("Two-factor authentication is on") }
    }

    fun disableTwoFactor(password: String, code: String) {
        if (!AccountValidation.isSecondFactor(code)) {
            _uiState.value = _uiState.value.copy(error = "Enter a 6-digit authenticator code or a recovery code")
            return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            try {
                apiClient.disableTwoFactor(password, code.trim())
                finishFlow("Two-factor authentication is off")
            } catch (e: Exception) {
                fail(e, "Couldn't turn off 2FA")
            }
        }
    }
}
