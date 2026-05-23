package com.gcc.server.db.tables

import com.gcc.shared.model.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * JSON config shared across all JSONB column definitions.
 * ignoreUnknownKeys handles forward-compatibility with gcc-site's Drizzle schema.
 *
 * `internal` so the per-domain Tables files in this package can share it.
 */
internal val dbJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Helper: inline jsonb column with kotlinx.serialization
// ---------------------------------------------------------------------------
internal inline fun <reified T : Any> Table.jsonbCol(name: String) = jsonb<T>(
    name,
    { dbJson.encodeToString(it) },
    { dbJson.decodeFromString(it) }
)

