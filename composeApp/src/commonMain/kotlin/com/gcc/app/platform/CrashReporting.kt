package com.gcc.app.platform

import com.gcc.shared.api.GccApiClient

/**
 * [installCrashReporting] installs the platform's uncaught-exception hook.
 * Must run before anything else (including Koin startup) so a crash during
 * later initialization is still caught.
 *
 * [flushPendingCrashReport] reads any crash persisted by that hook during
 * the *previous* session, transmits it via [apiClient], logs it to
 * [DiagnosticsLog], and clears the local record. Needs a real [GccApiClient]
 * from Koin, so it must run after Koin has started -- a separate call from
 * [installCrashReporting], not the same one.
 */
expect fun installCrashReporting()
expect suspend fun flushPendingCrashReport(apiClient: GccApiClient)
