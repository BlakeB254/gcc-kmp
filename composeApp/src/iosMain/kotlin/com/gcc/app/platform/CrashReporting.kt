@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.gcc.app.platform

import com.gcc.shared.api.GccApiClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.datetime.Clock
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Field crash reporting, Apple-native strategy (2026 best practice):
 *
 *  1. **TestFlight/App Store Connect** collect full crash reports from
 *     TestFlight builds automatically (visible in Xcode Organizer and the
 *     TestFlight app's feedback); dSYMs ride along in the fastlane upload,
 *     so those reports arrive symbolicated. Nothing to do at runtime.
 *  2. **MetricKit** (`CrashDiagnosticsObserver`, Swift side) delivers crash +
 *     hang diagnostics on the *next launch* and we persist them as JSON under
 *     `Documents/gcc-crash-diagnostics/` — retrievable via the Files app
 *     (UIFileSharingEnabled) and surfaced in the in-app 🐞 panel below.
 *  3. **Kotlin/Native fatal exceptions** would otherwise appear in Apple
 *     reports as opaque `kotlin.native` aborts, so [installCrashReporting]
 *     also sets an unhandled-exception hook that writes the full Kotlin
 *     stack trace to disk before the process dies.
 *
 * On every launch, [flushPendingCrashReport] imports whatever the previous
 * session left behind into [DiagnosticsLog] (and transmits the Kotlin
 * fatal-exception case to the backend), so an error screenshot of the
 * 🐞 panel shows prior-session crashes too.
 */
actual fun installCrashReporting() {
    setUnhandledExceptionHook { throwable ->
        // Keep this hook minimal and allocation-light: the process terminates
        // (and Apple's crash reporter fires) as soon as it returns.
        runCatching {
            val report = buildString {
                appendLine("Kotlin fatal exception (previous session)")
                appendLine(throwable.toString())
                appendLine(throwable.stackTraceToString())
            }
            @Suppress("CAST_NEVER_SUCCEEDS")
            (report as NSString).writeToFile(
                lastKotlinCrashPath(),
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
            println("GCC-DIAG ERR [crash] Kotlin fatal: $throwable")
        }
    }
}

/** Shared Documents-dir path (also used by [ArEnvScanOverlay]'s capture
 *  output dir — same NSFileManager pattern, one implementation). */
internal fun documentsPath(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String) ?: NSTemporaryDirectory()

private fun lastKotlinCrashPath(): String = documentsPath() + "/gcc-last-kotlin-crash.txt"

/** Directory the Swift MetricKit observer writes diagnostic JSON into. */
private fun metricKitDirPath(): String = documentsPath() + "/gcc-crash-diagnostics"

actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {
    val fm = NSFileManager.defaultManager

    val kotlinCrashPath = lastKotlinCrashPath()
    if (fm.fileExistsAtPath(kotlinCrashPath)) {
        val text = NSString.stringWithContentsOfFile(
            kotlinCrashPath,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        DiagnosticsLog.error(
            "crash",
            "previous session died with a Kotlin fatal exception:\n${text?.take(1500) ?: "(unreadable)"}",
        )
        if (text != null) {
            runCatching {
                apiClient.reportCrash(
                    crashReportRequest(
                        deviceId = CoverageDeviceId.value,
                        platform = "ios",
                        exceptionMessage = text.lineSequence().drop(1).firstOrNull() ?: "unknown",
                        stackTrace = text,
                        occurredAt = Clock.System.now(),
                    ),
                )
                fm.removeItemAtPath(kotlinCrashPath, error = null)
            }
        }
    }

    val metricKitFiles = fm.contentsOfDirectoryAtPath(metricKitDirPath(), error = null)
        ?.filterIsInstance<String>()
        .orEmpty()
        .sorted()
    if (metricKitFiles.isNotEmpty()) {
        DiagnosticsLog.error(
            "crash",
            "${metricKitFiles.size} MetricKit crash/hang diagnostic(s) from earlier sessions " +
                "(latest: ${metricKitFiles.last()}) — uploading to the backend",
        )
        // Flight recorder Phase 1: these JSONs used to strand on-device
        // (Files app only). Upload each — full payload to R2 via
        // POST /api/crashes/diagnostics — and delete ONLY on success, same
        // retry-until-delivered contract as the Kotlin crash file above.
        var uploadedCount = 0
        for (name in metricKitFiles.filter { it.endsWith(".json") }) {
            val path = metricKitDirPath() + "/" + name
            val json = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
                ?: continue
            val result = runCatching {
                apiClient.uploadCrashDiagnostic(
                    metricKitDiagnosticRequest(
                        deviceId = CoverageDeviceId.value,
                        platform = "ios",
                        fileName = name,
                        payload = json,
                    ),
                )
            }
            val message = result.exceptionOrNull()?.message.orEmpty()
            when {
                result.isSuccess -> {
                    fm.removeItemAtPath(path, error = null)
                    uploadedCount++
                }
                // 429 = shared per-device throttle — transient, retry next launch.
                message.contains("HTTP 429") -> break
                // Other 4xx (413 too large, 400 bad name) are PERMANENT by the
                // route's contract — delete so one bad file can't wedge the
                // retry loop forever.
                message.contains("HTTP 4") -> {
                    DiagnosticsLog.error("crash", "MetricKit diagnostic $name rejected ($message) — dropped")
                    fm.removeItemAtPath(path, error = null)
                }
                // Offline / 500 / 501 (R2 unprovisioned): keep everything,
                // retry next launch — later files would fail the same way.
                else -> break
            }
        }
        if (uploadedCount > 0) {
            DiagnosticsLog.info("crash", "$uploadedCount MetricKit diagnostic(s) uploaded to the backend")
        }
    }
    // Prune so the folder can't grow unbounded across many test sessions
    // (e.g. a device that stays offline for weeks).
    val remaining = fm.contentsOfDirectoryAtPath(metricKitDirPath(), error = null)
        ?.filterIsInstance<String>()
        .orEmpty()
        .sorted()
    if (remaining.size > 20) {
        remaining.dropLast(20).forEach { name ->
            fm.removeItemAtPath(metricKitDirPath() + "/" + name, error = null)
        }
    }
}
