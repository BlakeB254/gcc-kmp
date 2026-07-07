package com.gcc.shared.geo

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-checks [gridCell10m] against hand-computed values from the same
 * formula the TS server uses (site/src/lib/world-observations.ts's
 * `gridCell()`), verified by running the actual TS constants/formula
 * (METERS_PER_DEG_LAT=111000, METERS_PER_DEG_LNG=82300) through node:
 *
 * ```
 * gridCell(41.8781, -87.6298)   -> "464846:-721194"
 * gridCell(0, 0)                -> "0:0"
 * gridCell(-1.5, -0.0005)       -> "-16650:-5"
 * gridCell(41.87909, -87.62978) -> "464857:-721194"
 * ```
 *
 * Any change to [gridCell10m]'s formula or constants that breaks these
 * would silently desync client-computed cell ids from the server's, so
 * this test is the tripwire for that.
 */
class GridCellTest {

    @Test
    fun `matches TS gridCell for downtown Chicago coordinates`() {
        assertEquals("464846:-721194", gridCell10m(41.8781, -87.6298))
    }

    @Test
    fun `matches TS gridCell at the origin`() {
        assertEquals("0:0", gridCell10m(0.0, 0.0))
    }

    @Test
    fun `matches TS gridCell for negative lat and lng`() {
        assertEquals("-16650:-5", gridCell10m(-1.5, -0.0005))
    }

    @Test
    fun `matches TS gridCell for a nearby but distinct point`() {
        assertEquals("464857:-721194", gridCell10m(41.87909, -87.62978))
    }
}
