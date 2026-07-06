package com.gcc.app.api

import com.gcc.shared.api.fetchAllPages
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the paging loop the admin workers list relies on: a full page means
 *  "ask for the next one", a short page means "done". A hard-coded single
 *  fetch is how the admin tab lost 7 of 27 workers. */
class FetchAllPagesTest {

    @Test
    fun shortFirstPageStopsAfterOneCall() = runTest {
        val calls = mutableListOf<Int>()
        val result = fetchAllPages(pageSize = 20) { page, limit ->
            calls += page
            List(7) { "p$page-i$it-l$limit" }
        }
        assertEquals(7, result.size)
        assertEquals(listOf(1), calls)
    }

    @Test
    fun fullPagesKeepFetchingUntilShortPage() = runTest {
        // 27 items at pageSize 20 → page 1 full (20), page 2 short (7)
        val items = (1..27).toList()
        val calls = mutableListOf<Int>()
        val result = fetchAllPages(pageSize = 20) { page, limit ->
            calls += page
            items.drop((page - 1) * limit).take(limit)
        }
        assertEquals(items, result)
        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun exactMultipleFetchesOneTrailingEmptyPage() = runTest {
        val items = (1..40).toList()
        val calls = mutableListOf<Int>()
        val result = fetchAllPages(pageSize = 20) { page, limit ->
            calls += page
            items.drop((page - 1) * limit).take(limit)
        }
        assertEquals(items, result)
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test
    fun maxPagesBoundsRunawayLoops() = runTest {
        var calls = 0
        val result = fetchAllPages(pageSize = 2, maxPages = 3) { page, limit ->
            calls++
            List(limit) { "p$page-i$it" }
        }
        assertEquals(6, result.size)
        assertEquals(3, calls)
    }
}
