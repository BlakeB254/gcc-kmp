package com.gcc.app.platform

/**
 * Platform-specific API base URL.
 *
 * Each platform resolves to the correct localhost address:
 * - Android emulator: 10.0.2.2 (maps to host machine)
 * - iOS simulator: localhost
 * - Desktop: localhost
 */
expect fun getApiBaseUrl(): String
