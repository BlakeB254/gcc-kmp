package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ApiResult<out T> {
    @Serializable
    data class Success<T>(val data: T) : ApiResult<T>()
    @Serializable
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}

@Serializable
data class ApiResponse<T>(
    val data: T,
    val success: Boolean = true,
    val message: String? = null
)

@Serializable
data class PaginatedResponse<T>(
    val docs: List<T>,
    val totalDocs: Int,
    val totalPages: Int,
    val page: Int,
    val limit: Int,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean
)

@Serializable
data class AuthResponse(
    val token: String? = null,
    val worker: Worker? = null,
    val exp: Long = 0,
    val requiresTwoFactor: Boolean = false,
    val twoFactorToken: String? = null,
)
