package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific SqlDriver for the shared RouteCacheStore (and any future
 * SQLDelight-backed cache). See docs/superpowers/specs/2026-07-04-sqldelight-
 * route-cache-design.md.
 */
expect fun createSqlDriver(): SqlDriver
