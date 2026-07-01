package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString
import platform.Security.SecAddSharedWebCredential

actual fun getApiBaseUrl(): String = "https://api.globalcommunitycleaners.org"

actual fun getPlatformName(): String = "ios"

actual fun openUrl(url: String) {
    val nsUrl = platform.Foundation.NSURL.URLWithString(url) ?: return
    platform.UIKit.UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>()) { _ -> }
}

@OptIn(ExperimentalForeignApi::class)
actual fun appVersion(): String {
    val dict = platform.Foundation.NSBundle.mainBundle.infoDictionary
    val version = dict?.get("CFBundleShortVersionString") as? String ?: "Unknown"
    val build = dict?.get("CFBundleVersion") as? String ?: "0"
    return "$version ($build)"
}

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
