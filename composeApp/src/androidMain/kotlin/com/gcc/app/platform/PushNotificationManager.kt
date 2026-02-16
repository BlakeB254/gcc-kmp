package com.gcc.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "PushNotificationManager"

/**
 * Android push notification manager.
 *
 * FCM integration requires adding Firebase to the project (google-services.json
 * and the firebase-messaging dependency). This implementation provides the
 * structure and will return the FCM token when Firebase is configured.
 *
 * For now, notification permission is checked but token retrieval returns null
 * until FirebaseMessaging is integrated.
 */
actual class PushNotificationManager actual constructor() {

    actual suspend fun registerForPushNotifications(): String? {
        val ctx = appContext
        if (ctx == null) {
            Log.w(TAG, "Context not initialized")
            return null
        }

        // Android 13+ requires POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted")
                return null
            }
        }

        // FCM token retrieval — uncomment when Firebase is added:
        // return suspendCancellableCoroutine { cont ->
        //     FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        //         if (task.isSuccessful) cont.resume(task.result)
        //         else cont.resume(null)
        //     }
        // }
        Log.i(TAG, "FCM not yet configured — returning null token")
        return null
    }

    actual fun unregister() {
        // FirebaseMessaging.getInstance().deleteToken()
        Log.i(TAG, "FCM not yet configured — unregister is a no-op")
    }

    companion object {
        @Volatile
        private var appContext: Context? = null

        fun initContext(context: Context) {
            appContext = context.applicationContext
        }
    }
}
