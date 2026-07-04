package com.gcc.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.WorldObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sampleCell(id: String, state: String = "fresh") = MapCoverageCell(
    locationCellId = id,
    lat = 41.85,
    lng = -87.72,
    state = state,
    lastScannedAt = "2026-07-04T12:00:00Z",
    totalDistanceMeters = 42.0,
)

private fun sampleObservation(id: Int) = WorldObservation(
    id = id,
    lat = 41.85,
    lng = -87.72,
    itemType = "bottle",
    state = "dirty",
    sourceType = "route_scan",
)

private fun freshInMemoryStore(): ScanAreaCacheStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    GccDatabase.Schema.create(driver)
    return ScanAreaCacheStore(driver)
}

class ScanAreaCacheStoreTest {

    @Test
    fun missReturnsNull() {
        val store = freshInMemoryStore()
        assertNull(store.get("scan:4185:-8772"))
    }

    @Test
    fun putThenGetRoundTrips() {
        val store = freshInMemoryStore()
        val key = ScanAreaCacheStore.areaKeyFor(41.858, -87.72)
        store.put(key, listOf(sampleCell("4185:-8772")), listOf(sampleObservation(7)), nowMs = 1000L)

        val cached = store.get(key)!!
        assertEquals(key, cached.areaKey)
        assertEquals("4185:-8772", cached.coverageCells.single().locationCellId)
        assertEquals(7, cached.observations.single().id)
        assertEquals(1000L, cached.cachedAtMs)
    }

    @Test
    fun putReplacesExistingSnapshotForSameArea() {
        val store = freshInMemoryStore()
        val key = "scan:1:2"
        store.put(key, listOf(sampleCell("a")), emptyList(), nowMs = 1L)
        store.put(key, listOf(sampleCell("b", state = "stale")), emptyList(), nowMs = 2L)

        val cached = store.get(key)!!
        assertEquals("b", cached.coverageCells.single().locationCellId)
        assertEquals(2L, cached.cachedAtMs)
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun pruneDropsOnlyStaleSnapshots() {
        val store = freshInMemoryStore()
        store.put("scan:old", emptyList(), emptyList(), nowMs = 0L)
        store.put("scan:new", emptyList(), emptyList(), nowMs = 500L)

        store.prune(nowMs = 1000L, maxAgeMs = 600L)

        assertNull(store.get("scan:old"))
        assertEquals("scan:new", store.getAll().single().areaKey)
    }

    @Test
    fun areaKeyIsStableWithinATileAndDiffersAcrossTiles() {
        val a = ScanAreaCacheStore.areaKeyFor(41.8501, -87.7201)
        val b = ScanAreaCacheStore.areaKeyFor(41.8599, -87.7299)
        val c = ScanAreaCacheStore.areaKeyFor(41.8701, -87.7201)
        assertEquals(a, b) // same 0.01° tile
        assertTrue(a != c)
    }
}
