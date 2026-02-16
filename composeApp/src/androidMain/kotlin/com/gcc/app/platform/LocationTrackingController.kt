package com.gcc.app.platform

import android.content.Context
import android.content.Intent
import com.gcc.app.service.LocationTrackingService

actual class LocationTrackingController {
    actual fun startTracking() {
        val ctx = context ?: return
        val intent = Intent(ctx, LocationTrackingService::class.java)
        ctx.startForegroundService(intent)
    }

    actual fun stopTracking() {
        val ctx = context ?: return
        ctx.stopService(Intent(ctx, LocationTrackingService::class.java))
    }

    companion object {
        private var context: Context? = null

        fun initContext(context: Context) {
            this.context = context.applicationContext
        }
    }
}
