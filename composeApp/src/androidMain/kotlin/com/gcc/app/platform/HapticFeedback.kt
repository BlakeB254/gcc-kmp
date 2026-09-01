package com.gcc.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Light impact haptic matching the iOS actual
 * (UIImpactFeedbackGenerator(.light)): one short low-amplitude tick per
 * confirm/capture event. Context arrives via [initHapticFeedbackContext]
 * from MainActivity.onCreate — the established initXContext bridge pattern
 * in this source set.
 */
private var hapticAppContext: Context? = null

fun initHapticFeedbackContext(context: Context) {
    hapticAppContext = context.applicationContext
}

actual fun triggerHapticFeedback() {
    val ctx = hapticAppContext ?: return
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return
    if (!vibrator.hasVibrator()) return
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // EFFECT_TICK is the platform's calibrated "light impact".
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    } else {
        // API 26-28: short low-amplitude one-shot approximation.
        VibrationEffect.createOneShot(20, 120)
    }
    vibrator.vibrate(effect)
}
