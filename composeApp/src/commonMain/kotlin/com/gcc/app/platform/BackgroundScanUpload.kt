package com.gcc.app.platform

/**
 * Ask the host OS for a future background-processing window to drain durable
 * scan artifacts. This is scheduling, not a permission prompt: iOS controls
 * execution through Background App Refresh and may defer the request.
 */
expect fun scheduleBackgroundScanUpload()
