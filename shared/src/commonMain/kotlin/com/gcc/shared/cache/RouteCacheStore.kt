package com.gcc.shared.cache

import app.cash.sqldelight.db.SqlDriver
import com.gcc.shared.model.Route
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Local cache of the last successfully-fetched routes list, backed by
 * SQLDelight. One row per route: id (upsert identity) + the whole DTO
 * serialized as JSON -- Route has nested GeoJSON geometry and metadata,
 * so flattening into columns would be a
 * large, brittle schema that drifts from the Kotlin model over time.
 */
class RouteCacheStore(driver: SqlDriver) {
    private val queries = GccDatabase(driver).routeCacheQueries

    fun replaceAll(routes: List<Route>) {
        queries.transaction {
            queries.clear()
            routes.forEach { route ->
                queries.insertOrReplace(route.id.toLong(), GccJson.encodeToString(route))
            }
        }
    }

    fun getAll(): List<Route> =
        queries.selectAll().executeAsList().mapNotNull { row ->
            try {
                GccJson.decodeFromString(row.json)
            } catch (e: Exception) {
                // A single corrupt/schema-mismatched row must not break the
                // whole cache read -- skip it, keep the rest.
                null
            }
        }
}
