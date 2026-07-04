package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.gcc.shared.cache.GccDatabase

actual fun createSqlDriver(): SqlDriver = NativeSqliteDriver(GccDatabase.Schema, "gcc.db")
