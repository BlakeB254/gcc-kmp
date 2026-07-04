package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.cache.GccDatabase

actual fun createSqlDriver(): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:gcc.db").also { GccDatabase.Schema.create(it) }
