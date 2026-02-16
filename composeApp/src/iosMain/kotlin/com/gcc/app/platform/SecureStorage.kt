package com.gcc.app.platform

import com.russhwolf.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val SERVICE_NAME = "com.globalcommunitycleaners.worker"

/**
 * iOS secure storage backed by Keychain Services.
 *
 * Each key-value pair is stored as a `kSecClassGenericPassword` item with:
 * - `kSecAttrService` = bundle identifier (scopes to this app)
 * - `kSecAttrAccount` = the settings key
 * - `kSecValueData` = the value encoded as UTF-8
 * - `kSecAttrAccessible` = `kSecAttrAccessibleAfterFirstUnlock` (available
 *   in background after first device unlock — needed for background location)
 *
 * Implements the multiplatform-settings [Settings] interface so it can be
 * passed directly to [com.gcc.shared.auth.TokenManager].
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureStorage actual constructor() : Settings {

    override val keys: Set<String> get() = emptySet() // Keychain doesn't support key enumeration efficiently
    override val size: Int get() = 0

    override fun clear() {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE_NAME,
        )
        SecItemDelete(query as platform.CoreFoundation.CFDictionaryRef?)
    }

    override fun remove(key: String) {
        val query = baseQuery(key)
        SecItemDelete(query as platform.CoreFoundation.CFDictionaryRef?)
    }

    override fun hasKey(key: String): Boolean = getStringOrNull(key) != null

    override fun putInt(key: String, value: Int) = putString(key, value.toString())
    override fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue
    override fun getIntOrNull(key: String): Int? = getStringOrNull(key)?.toIntOrNull()

    override fun putLong(key: String, value: Long) = putString(key, value.toString())
    override fun getLong(key: String, defaultValue: Long): Long = getLongOrNull(key) ?: defaultValue
    override fun getLongOrNull(key: String): Long? = getStringOrNull(key)?.toLongOrNull()

    override fun putString(key: String, value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return

        // Try update first (most common path after initial save)
        val updateQuery = baseQuery(key)
        val updateAttrs = mapOf<Any?, Any?>(kSecValueData to data)
        val updateStatus = SecItemUpdate(
            updateQuery as platform.CoreFoundation.CFDictionaryRef?,
            updateAttrs as platform.CoreFoundation.CFDictionaryRef?,
        )

        if (updateStatus == errSecSuccess) return

        // Item doesn't exist yet — add it
        if (updateStatus == errSecItemNotFound) {
            val addQuery = baseQuery(key) + mapOf<Any?, Any?>(
                kSecValueData to data,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            )
            SecItemAdd(addQuery as platform.CoreFoundation.CFDictionaryRef?, null)
        }
    }

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? = memScoped {
        val query = baseQuery(key) + mapOf<Any?, Any?>(
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(
            query as platform.CoreFoundation.CFDictionaryRef?,
            result.ptr,
        )
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
    }

    override fun putFloat(key: String, value: Float) = putString(key, value.toString())
    override fun getFloat(key: String, defaultValue: Float): Float = getFloatOrNull(key) ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = getStringOrNull(key)?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) = putString(key, value.toString())
    override fun getDouble(key: String, defaultValue: Double): Double = getDoubleOrNull(key) ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = getStringOrNull(key)?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBooleanOrNull(key) ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = getStringOrNull(key)?.toBooleanStrictOrNull()

    private fun baseQuery(key: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE_NAME,
        kSecAttrAccount to key,
    )
}
