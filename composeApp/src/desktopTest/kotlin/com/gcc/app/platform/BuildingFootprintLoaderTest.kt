package com.gcc.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.app.ui.map.MappedBuildingRegistry
import com.gcc.app.ui.map.isInsideMappedBuilding
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.cache.BuildingFootprintStore
import com.gcc.shared.cache.GccDatabase
import com.gcc.shared.model.BuildingPolygon
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

// JVM-only (JdbcSqliteDriver) — lives in desktopTest, not commonTest, so it
// never breaks the iOS commonTest compile.
class BuildingFootprintLoaderTest {

    @AfterTest
    fun tearDown() = MappedBuildingRegistry.clear()

    private fun storeWithSquare(): BuildingFootprintStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GccDatabase.Schema.create(driver)
        val store = BuildingFootprintStore(driver)
        val key = BuildingFootprintStore.tileKeyFor(41.863, -87.715)
        val square = BuildingPolygon(
            polygon = listOf(
                listOf(-87.720, 41.860), listOf(-87.710, 41.860),
                listOf(-87.710, 41.866), listOf(-87.720, 41.866),
                listOf(-87.720, 41.860),
            ),
        )
        store.put(key, listOf(square), nowMs = 1_000L)
        return store
    }

    @Test
    fun cacheHitPopulatesRegistryWithoutFetch() = runTest {
        MappedBuildingRegistry.clear()
        val store = storeWithSquare()
        // apiClient that would fail if hit — proves the fresh cache-hit path
        // never touches the network.
        val apiClient = GccApiClient(baseUrl = "http://unused.invalid")
        val loader = BuildingFootprintLoader(apiClient, store)
        loader.ensureFootprintsFor(lat = 41.863, lng = -87.715, nowMs = 2_000L)
        assertTrue(MappedBuildingRegistry.hasFootprints())
        assertTrue(isInsideMappedBuilding(lat = 41.863, lng = -87.715))
    }
}
