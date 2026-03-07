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
    val password: String
)

@Serializable
data class Verify2FARequest(
    val twoFactorToken: String,
    val code: String,
)
