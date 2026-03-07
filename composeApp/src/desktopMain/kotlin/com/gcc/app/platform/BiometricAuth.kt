package com.gcc.app.platform

/**
 * Desktop BiometricAuth -- no biometric hardware, always passes through.
 */
actual class BiometricAuth actual constructor() {
    actual fun isBiometricAvailable(): Boolean = false
    actual suspend fun authenticate(reason: String): Boolean = true
}
