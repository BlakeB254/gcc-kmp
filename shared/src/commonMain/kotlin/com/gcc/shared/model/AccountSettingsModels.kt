package com.gcc.shared.model

import kotlinx.serialization.Serializable

// DTOs for the account-settings endpoints (/api/workers/me/*).
// See docs/superpowers/specs/2026-07-06-account-settings-design.md.

@Serializable
data class UpdateProfileRequest(
    val name: String,
)

@Serializable
data class UpdateProfileResponse(
    val name: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/** Generic acknowledgement for endpoints whose payload is just "it worked". */
@Serializable
data class AccountActionResponse(
    val ok: Boolean = true,
)

@Serializable
data class EmailChangeRequest(
    val newEmail: String,
    val password: String,
)

@Serializable
data class EmailChangeStarted(
    val pendingEmail: String,
    val expiresInMinutes: Int = 30,
)

@Serializable
data class EmailChangeConfirmRequest(
    val code: String,
)

@Serializable
data class EmailChangeConfirmed(
    val email: String,
)

@Serializable
data class PhoneChangeRequest(
    val newPhone: String,
    val password: String,
)

@Serializable
data class PhoneChangeResponse(
    val phone: String,
)

@Serializable
data class TwoFactorSetupRequest(
    val password: String,
)

@Serializable
data class TwoFactorSetupResponse(
    /** Base32 TOTP secret — shown as a copyable setup key. */
    val secret: String,
    /** otpauth:// URI — opening it hands the secret to the authenticator app. */
    val otpauthUri: String,
)

@Serializable
data class TwoFactorEnableRequest(
    val code: String,
)

@Serializable
data class TwoFactorEnableResponse(
    /** Plaintext recovery codes — shown exactly once, never retrievable again. */
    val recoveryCodes: List<String>,
)

@Serializable
data class TwoFactorDisableRequest(
    val password: String,
    val code: String,
)
