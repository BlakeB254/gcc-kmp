package com.gcc.app.platform

expect class BiometricAuth() {
    fun isBiometricAvailable(): Boolean
    suspend fun authenticate(reason: String): Boolean
}
