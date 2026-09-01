package com.gcc.app.platform

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.lang.ref.WeakReference

/**
 * FLAG_KEEP_SCREEN_ON on the host activity's window — scan sessions must
 * survive long capture walks without the screen sleeping (mirrors the iOS
 * actual's `isIdleTimerDisabled`). The activity arrives via
 * [initScreenWakeActivity] from MainActivity.onCreate (the established
 * initXContext bridge pattern in this source set).
 */
private var screenWakeActivity: WeakReference<Activity>? = null

fun initScreenWakeActivity(activity: Activity) {
    screenWakeActivity = WeakReference(activity)
}

actual fun setKeepScreenAwake(enabled: Boolean) {
    val activity = screenWakeActivity?.get() ?: return
    activity.runOnUiThread {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Keeps the screen awake for the composable's lifetime (scan screens). */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        setKeepScreenAwake(true)
        onDispose { setKeepScreenAwake(false) }
    }
}
