package com.gcc.app.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.gcc.shared.cache.GccDatabase

/**
 * Context must be initialized via [initSqlDriverContext] before first use
 * (called from MainActivity, alongside the existing initSecureStorageContext
 * call -- same pattern as SecureStorage.kt's Android actual).
 */
@Volatile
private var appContext: Context? = null

fun initSqlDriverContext(context: Context) {
    appContext = context.applicationContext
}

actual fun createSqlDriver(): SqlDriver {
    val ctx = requireNotNull(appContext) {
        "SqlDriverFactory: initSqlDriverContext() must be called before createSqlDriver()"
    }
    return AndroidSqliteDriver(GccDatabase.Schema, ctx, "gcc.db")
}
