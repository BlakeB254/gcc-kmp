package com.gcc.app.platform

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

actual class BiometricAuth actual constructor() {

    actual fun isBiometricAvailable(): Boolean {
        val activity = activityRef?.get() ?: return false
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    actual suspend fun authenticate(reason: String): Boolean {
        val activity = activityRef?.get() ?: return false
        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // Called on individual failed attempt — let the system retry
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("GCC Authentication")
                .setSubtitle(reason)
                .setNegativeButtonText("Cancel")
                .build()

            prompt.authenticate(info)
        }
    }

    companion object {
        private var activityRef: WeakReference<FragmentActivity>? = null

        fun initActivity(activity: FragmentActivity) {
            activityRef = WeakReference(activity)
        }
    }
}
