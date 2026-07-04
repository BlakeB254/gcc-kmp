package com.gcc.app.platform

import com.gcc.shared.api.GccApiClient

// Desktop is a dev target, not a real user deployment -- no crash capture needed.
actual fun installCrashReporting() {}
actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {}
