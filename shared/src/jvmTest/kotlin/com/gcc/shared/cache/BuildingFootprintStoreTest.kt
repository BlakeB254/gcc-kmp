package com.gcc.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.model.BuildingPolygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingFootprintStoreTest {
    private fun newStore(): BuildingFootprintStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GccDatabase.Schema.create(driver)
        return BuildingFootprintStore(driver)
    }

    private val square = BuildingPolygon(
        polygon = listOf(
            listOf(-87.720, 41.860), listOf(-87.710, 41.860),
            listOf(-87.710, 41.866), listOf(-87.720, 41.866),
            listOf(-87.720, 41.860),
        ),
        buildingType = "house",
    )

    @Test
    fun putThenGetRoundTrips() {
        val store = newStore()
        val key = BuildingFootprintStore.tileKeyFor(41.863, -87.715)
        store.put(key, listOf(square), nowMs = 1_000L)
        val cached = store.get(key)
        assertEquals(1, cached?.buildings?.size)
        assertEquals("house", cached?.buildings?.first()?.buildingType)
        assertEquals(listOf(-87.720, 41.860), cached?.buildings?.first()?.polygon?.first())
    }

    @Test
    fun getMissingTileIsNull() {
        assertNull(newStore().get("b:9999:9999"))
    }

    @Test
    fun pruneDropsStaleRows() {
        val store = newStore()
        val key = BuildingFootprintStore.tileKeyFor(41.863, -87.715)
        store.put(key, listOf(square), nowMs = 0L)
        store.prune(nowMs = BuildingFootprintStore.DEFAULT_MAX_AGE_MS + 1L)
        assertNull(store.get(key))
    }

    @Test
    fun tileKeyAndBboxAgree() {
        val key = BuildingFootprintStore.tileKeyFor(41.863, -87.715)
        assertEquals("b:4186:-8772", key)
        val parts = BuildingFootprintStore.tileBbox(key).split(",").map(String::toDouble)
        assertTrue(-87.715 in parts[0]..parts[2])
        assertTrue(41.863 in parts[1]..parts[3])
    }
}
