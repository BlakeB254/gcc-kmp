package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

actual class BiometricAuth actual constructor() {

    @OptIn(ExperimentalForeignApi::class)
    actual fun isBiometricAvailable(): Boolean {
        // Fresh LAContext each time — previous context may be invalidated after use
        return LAContext().canEvaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            error = null,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun authenticate(reason: String): Boolean {
        // LAContext requires the app to be in the foreground on the main thread
        return withContext(Dispatchers.Main) {
            val context = LAContext() // Must be fresh per authentication attempt
            if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)) {
                return@withContext false
            }

            suspendCancellableCoroutine { continuation ->
                context.evaluatePolicy(
                    LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                    localizedReason = reason,
                ) { success, _ ->
                    continuation.resume(success)
                }
            }
        }
    }
}
