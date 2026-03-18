package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString
import platform.Security.SecAddSharedWebCredential

actual fun getApiBaseUrl(): String = "http://100.67.196.13:7310"

actual fun getPlatformName(): String = "ios"

@OptIn(ExperimentalForeignApi::class)
actual fun saveToSystemCredentials(email: String, password: String) {
    @Suppress("UNCHECKED_CAST")
    val domain = CFBridgingRetain("globalcommunitycleaners.org" as NSString) as platform.CoreFoundation.CFStringRef?
    @Suppress("UNCHECKED_CAST")
    val account = CFBridgingRetain(email as NSString) as platform.CoreFoundation.CFStringRef?
    @Suppress("UNCHECKED_CAST")
    val pass = CFBridgingRetain(password as NSString) as platform.CoreFoundation.CFStringRef?
    SecAddSharedWebCredential(domain, account, pass) { _ ->
        // Best effort — ignore errors (user may dismiss the save prompt)
    }
}
