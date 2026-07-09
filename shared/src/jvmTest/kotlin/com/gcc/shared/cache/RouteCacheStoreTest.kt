package com.gcc.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun sampleRoute(id: Int, code: String) = Route(
    id = id,
    code = code,
    displayName = "Route $code",
    serviceAreaId = 1,
    geometry = null,
    blockBoundary = null,
    innerBoundary = null,
    status = RouteStatus.PUBLISHED,
    distanceMeters = 1000.0,
    estimatedDurationMinutes = 30,
    payoutAmount = 500,
    imageUrl = null,
    imageLocationLat = null,
    imageLocationLng = null,
    description = null,
    createdAt = Instant.parse("2026-07-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-07-01T00:00:00Z"),
)

private fun freshInMemoryStore(): RouteCacheStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    GccDatabase.Schema.create(driver)
    return RouteCacheStore(driver)
}

class RouteCacheStoreTest {

    @Test
    fun emptyCacheReturnsEmptyList() {
        val store = freshInMemoryStore()
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun replaceAllThenGetAllRoundTrips() {
        val store = freshInMemoryStore()
        val routes = listOf(sampleRoute(1, "R1"), sampleRoute(2, "R2"))

        store.replaceAll(routes)

        assertEquals(routes, store.getAll())
    }

    @Test
    fun secondReplaceAllFullyReplacesFirst() {
        val store = freshInMemoryStore()
        store.replaceAll(listOf(sampleRoute(1, "R1")))

        store.replaceAll(listOf(sampleRoute(2, "R2")))

        assertEquals(listOf(sampleRoute(2, "R2")), store.getAll())
    }

    @Test
    fun corruptRowIsSkippedNotCrashed() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GccDatabase.Schema.create(driver)
        val store = RouteCacheStore(driver)
        store.replaceAll(listOf(sampleRoute(1, "R1")))
        // Insert a second, corrupt row directly, bypassing RouteCacheStore's
        // normal JSON-encoding path, to simulate a schema-mismatched/corrupted row.
        GccDatabase(driver).routeCacheQueries.insertOrReplace(2L, "not valid json")

        val result = store.getAll()

        assertEquals(listOf(sampleRoute(1, "R1")), result)
    }
}
