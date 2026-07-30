package com.gcc.app.platform

import com.gcc.app.platform.capture.CaptureChunkUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Swift registers/schedules BGProcessingTask and calls back through this
 * bridge. Kotlin owns the actual queues, keeping background and foreground
 * drains byte-identical.
 */
object BackgroundScanUploadBridge {
    var scheduleCallback: (() -> Unit)? = null

    fun schedule() {
        scheduleCallback?.invoke()
    }

    fun flushPending(onComplete: (Boolean) -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val succeeded = try {
                val koin = KoinPlatform.getKoin()
                koin.getOrNull<CaptureChunkUploader>()?.flushPending()
                koin.getOrNull<EnvScanSessionUploader>()?.flushPending()
                true
            } catch (e: Exception) {
                DiagnosticsLog.error("background-upload", "BGProcessing flush failed", e)
                false
            }
            onComplete(succeeded)
        }
    }
}

actual fun scheduleBackgroundScanUpload() {
    BackgroundScanUploadBridge.schedule()
}
