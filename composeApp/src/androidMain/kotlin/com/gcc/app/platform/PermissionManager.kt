package com.gcc.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Android permission manager -- uses ContextCompat for status checks and
 * ActivityResultContracts.RequestPermission() for the real runtime dialog.
 *
 * Context/activity acquisition:
 * Since the expect declaration requires a no-arg constructor, we use a companion
 * object bridge (same pattern as [BiometricAuth] and [PushNotificationManager]).
 * [initActivity] must be called once from [com.gcc.app.MainActivity.onCreate],
 * *before* the activity moves past CREATED -- registerForActivityResult requires
 * registration to happen before the owning activity is STARTED.
 */
actual class PermissionManager actual constructor() {

    actual fun checkPermission(permission: Permission): PermissionStatus {
        val manifestPermission = permission.toAndroidPermission()
            ?: return PermissionStatus.GRANTED // Not a runtime permission on this OS version.

        val ctx = appContext ?: return PermissionStatus.NOT_DETERMINED
        val granted = ContextCompat.checkSelfPermission(ctx, manifestPermission) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    actual suspend fun requestPermission(permission: Permission): PermissionStatus {
        val manifestPermission = permission.toAndroidPermission()
            ?: return PermissionStatus.GRANTED

        if (checkPermission(permission) == PermissionStatus.GRANTED) {
            return PermissionStatus.GRANTED
        }

        // `pendingContinuation`/`activeLauncher` are shared, companion-level state:
        // the ActivityResultLauncher callback can only ever resume ONE continuation
        // (whichever launch() call happened most recently -- the Android permission
        // dialog fires a single callback). If two callers raced into this function
        // concurrently without the mutex below, the second call's assignment on the
        // `pendingContinuation = continuation` line would silently overwrite the
        // first caller's continuation, stranding it forever (its coroutine would
        // never resume). Worse, the first (now-orphaned) continuation's
        // `invokeOnCancellation` could still fire later and null out the SECOND
        // (legitimately pending) continuation's slot, stranding that one too.
        //
        // requestMutex serializes access to this section so only one
        // suspendCancellableCoroutine block can be "in flight" at a time. A second
        // concurrent caller (e.g. two call sites both requesting Permission.CAMERA)
        // suspends on the mutex until the first request resolves, then re-checks
        // checkPermission() below -- if the first request's dialog already
        // resolved the permission, the second caller returns that real answer
        // without launching a second system dialog.
        return requestMutex.withLock {
            if (checkPermission(permission) == PermissionStatus.GRANTED) {
                return@withLock PermissionStatus.GRANTED
            }

            val activeLauncher = launcher
            if (activeLauncher == null) {
                // initActivity() hasn't run yet (e.g. called before MainActivity.onCreate).
                return@withLock PermissionStatus.NOT_DETERMINED
            }

            markRequested(manifestPermission)

            suspendCancellableCoroutine { continuation ->
                pendingContinuation = continuation
                activeLauncher.launch(manifestPermission)
                continuation.invokeOnCancellation { pendingContinuation = null }
            }
        }
    }

    actual fun isPermanentlyDenied(permission: Permission): Boolean {
        val manifestPermission = permission.toAndroidPermission() ?: return false
        val activity = activityRef?.get() ?: return false

        if (checkPermission(permission) == PermissionStatus.GRANTED) return false

        // shouldShowRequestPermissionRationale() is false both *before* the first
        // request and *after* a "don't ask again" denial. Disambiguate using
        // hasRequestedBefore(), persisted so it survives process death.
        val shouldShowRationale = activity.shouldShowRequestPermissionRationale(manifestPermission)
        return !shouldShowRationale && hasRequestedBefore(manifestPermission)
    }

    private fun Permission.toAndroidPermission(): String? = when (this) {
        Permission.CAMERA -> Manifest.permission.CAMERA
        Permission.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        Permission.LOCATION_BACKGROUND ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            } else {
                null // Background location is implicit pre-Android 10.
            }
        Permission.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null // No runtime permission required pre-Android 13.
            }
    }

    private fun markRequested(manifestPermission: String) {
        val ctx = appContext ?: return
        prefs(ctx).edit().putBoolean(manifestPermission, true).apply()
    }

    private fun hasRequestedBefore(manifestPermission: String): Boolean {
        val ctx = appContext ?: return false
        return prefs(ctx).getBoolean(manifestPermission, false)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("gcc_permission_manager", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var appContext: Context? = null

        @Volatile
        private var activityRef: WeakReference<FragmentActivity>? = null

        @Volatile
        private var launcher: ActivityResultLauncher<String>? = null

        @Volatile
        private var pendingContinuation: CancellableContinuation<PermissionStatus>? = null

        // Serializes requestPermission() so at most one system permission dialog
        // (and one pendingContinuation) is in flight at a time. See the comment
        // in requestPermission() for the concurrency hazard this prevents.
        private val requestMutex = Mutex()

        /**
         * Register the runtime-permission launcher and store the activity/context.
         * Must be called from [com.gcc.app.MainActivity.onCreate] before the
         * activity moves past CREATED (registerForActivityResult requirement).
         */
        fun initActivity(activity: FragmentActivity) {
            appContext = activity.applicationContext
            activityRef = WeakReference(activity)
            launcher = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                val continuation = pendingContinuation
                pendingContinuation = null
                if (continuation != null && continuation.isActive) {
                    continuation.resume(
                        if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
                    )
                }
            }
        }
    }
}
