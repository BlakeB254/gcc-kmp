package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

actual class BiometricAuth actual constructor() {

    private val context = LAContext()

    @OptIn(ExperimentalForeignApi::class)
    actual fun isBiometricAvailable(): Boolean {
        return context.canEvaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            error = null,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun authenticate(reason: String): Boolean {
        if (!isBiometricAvailable()) return false

        return suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                localizedReason = reason,
            ) { success, _ ->
                continuation.resume(success)
            }
        }
    }
}
