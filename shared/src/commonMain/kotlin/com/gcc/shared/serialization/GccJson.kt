package com.gcc.shared.serialization

import kotlinx.serialization.json.Json

/**
 * Shared Json configuration used across the GCC codebase.
 * Consolidates the repeated `Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }`
 * pattern into a single instance.
 */
val GccJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}
