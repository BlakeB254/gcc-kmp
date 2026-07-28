package com.gcc.app.platform

import android.content.Context
import com.gcc.shared.api.GccApiClient
import kotlinx.datetime.Clock
import java.io.File

/**
 * Context must be initialized via [initCrashReportingContext] before
 * [installCrashReporting] is called -- the uncaught-exception hook captures
 * [crashFile] by reference, and needs the context to know where to write.
 */
@Volatile
private var appContext: Context? = null

fun initCrashReportingContext(context: Context) {
    appContext = context.applicationContext
}

private fun crashFile(): File? = appContext?.filesDir?.resolve("gcc-last-crash.txt")

actual fun installCrashReporting() {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            val report = buildString {
                appendLine("Kotlin fatal exception (previous session)")
                appendLine(throwable.toString())
                appendLine(throwable.stackTraceToString())
            }
            crashFile()?.writeText(report)
        }
        // Delegate to the previous handler so the app's real crash/exit
        // behavior is unchanged -- this only adds a side effect.
        previousHandler?.uncaughtException(thread, throwable)
    }
}

actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {
    val file = crashFile()?.takeIf { it.exists() } ?: return
    val text = runCatching { file.readText() }.getOrNull() ?: return
    DiagnosticsLog.error("crash", text.take(1500))
    runCatching {
        apiClient.reportCrash(
            crashReportRequest(
                deviceId = CoverageDeviceId.value,
                platform = "android",
                exceptionMessage = text.lineSequence().drop(1).firstOrNull() ?: "unknown",
                stackTrace = text,
                occurredAt = Clock.System.now(),
            ),
        )
        file.delete()
    }
}
