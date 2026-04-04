package com.gcc.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

actual fun getApiBaseUrl(): String = "https://api.globalcommunitycleaners.org"

actual fun getPlatformName(): String = "android"

actual fun openUrl(url: String) {
    val ctx = PlatformUrlOpener.appContext ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}

object PlatformUrlOpener {
    @Volatile
    var appContext: Context? = null

    fun initContext(context: Context) {
        appContext = context.applicationContext
    }
}

actual fun saveToSystemCredentials(email: String, password: String) {
    // No-op on Android (credentials stored in app Keychain only)
}
