package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val phone: String,
    val password: String,
    val addressStreet: String? = null,
    val addressCity: String? = null,
    val addressState: String? = null,
    val addressZip: String? = null,
)

@Serializable
data class Verify2FARequest(
    val twoFactorToken: String,
    val code: String,
)

@Serializable
data class VerificationDocumentSubmission(
    val governmentIdUrl: String,
    val proofOfAddressUrl: String,
)

@Serializable
data class AppleAuthRequest(
    val identityToken: String,
    val fullName: String? = null,
    val email: String? = null,
    val nonce: String? = null,
)

@Serializable
data class GoogleAuthRequest(
    val idToken: String,
)
