package com.gcc.app.platform

import com.russhwolf.settings.Settings
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
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
 * Uses CFDictionary APIs directly for Keychain queries since Kotlin/Native
 * does not implicitly bridge Kotlin Maps to CFDictionaryRef.
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureStorage actual constructor() : Settings {

    override val keys: Set<String> get() = emptySet()
    override val size: Int get() = 0

    override fun clear() {
        val query = cfDict(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to CFBridgingRetain(SERVICE_NAME as NSString),
        )
        SecItemDelete(query)
    }

    override fun remove(key: String) {
        SecItemDelete(baseQuery(key))
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
        val dataRef = CFBridgingRetain(data)

        // Try update first
        val updateQuery = baseQuery(key)
        val updateAttrs = cfDict(kSecValueData to dataRef)
        val updateStatus = SecItemUpdate(updateQuery, updateAttrs)

        if (updateStatus == errSecSuccess) return

        // Item doesn't exist yet — add it
        if (updateStatus == errSecItemNotFound) {
            val addQuery = cfDict(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to CFBridgingRetain(SERVICE_NAME as NSString),
                kSecAttrAccount to CFBridgingRetain(key as NSString),
                kSecValueData to dataRef,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            )
            val addStatus = SecItemAdd(addQuery, null)
            if (addStatus == errSecDuplicateItem) {
                // Stale item exists — delete and retry
                SecItemDelete(baseQuery(key))
                SecItemAdd(addQuery, null)
            }
        } else {
            // Unexpected error on update — delete and re-add
            NSLog("GCC Keychain: update failed for key=$key status=$updateStatus, deleting and re-adding")
            SecItemDelete(baseQuery(key))
            val addQuery = cfDict(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to CFBridgingRetain(SERVICE_NAME as NSString),
                kSecAttrAccount to CFBridgingRetain(key as NSString),
                kSecValueData to dataRef,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            )
            SecItemAdd(addQuery, null)
        }
    }

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? = memScoped {
        val query = cfDict(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to CFBridgingRetain(SERVICE_NAME as NSString),
            kSecAttrAccount to CFBridgingRetain(key as NSString),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
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

    private fun baseQuery(key: String): CFDictionaryRef? = cfDict(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to CFBridgingRetain(SERVICE_NAME as NSString),
        kSecAttrAccount to CFBridgingRetain(key as NSString),
    )

    private fun cfDict(vararg entries: Pair<COpaquePointer?, COpaquePointer?>): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, entries.size.toLong(),
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        for ((k, v) in entries) {
            CFDictionarySetValue(dict, k, v)
        }
        return dict
    }
}
