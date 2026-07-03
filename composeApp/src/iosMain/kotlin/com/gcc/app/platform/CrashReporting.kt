@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi
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
 * On every launch, [installCrashReporting] imports whatever the previous
 * session left behind into [DiagnosticsLog], so an error screenshot of the
 * 🐞 panel shows prior-session crashes too.
 */
fun installCrashReporting() {
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
    importPreviousSessionCrashes()
}

private fun documentsPath(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String) ?: NSTemporaryDirectory()

private fun lastKotlinCrashPath(): String = documentsPath() + "/gcc-last-kotlin-crash.txt"

/** Directory the Swift MetricKit observer writes diagnostic JSON into. */
private fun metricKitDirPath(): String = documentsPath() + "/gcc-crash-diagnostics"

private fun importPreviousSessionCrashes() {
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
        fm.removeItemAtPath(kotlinCrashPath, error = null)
    }

    val metricKitFiles = fm.contentsOfDirectoryAtPath(metricKitDirPath(), error = null)
        ?.filterIsInstance<String>()
        .orEmpty()
        .sorted()
    if (metricKitFiles.isNotEmpty()) {
        DiagnosticsLog.error(
            "crash",
            "${metricKitFiles.size} MetricKit crash/hang diagnostic(s) from earlier sessions " +
                "(latest: ${metricKitFiles.last()}) — full JSON in Files app → GCC → gcc-crash-diagnostics",
        )
    }
    // Prune so the folder can't grow unbounded across many test sessions.
    if (metricKitFiles.size > 20) {
        metricKitFiles.dropLast(20).forEach { name ->
            fm.removeItemAtPath(metricKitDirPath() + "/" + name, error = null)
        }
    }
}
