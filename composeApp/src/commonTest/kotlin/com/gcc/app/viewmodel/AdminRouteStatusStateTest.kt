package com.gcc.app.viewmodel

import com.gcc.shared.model.GeoJSONMultiPolygon
import com.gcc.shared.model.GeoJSONPolygon
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRouteStatusStateTest {
    private val boundary = GeoJSONPolygon(
        coordinates = listOf(
            listOf(
                listOf(-87.72, 41.86), listOf(-87.71, 41.86),
                listOf(-87.72, 41.87), listOf(-87.72, 41.86),
            ),
        ),
    )
    private val innerBoundary = GeoJSONMultiPolygon(
        coordinates = listOf(
            listOf(
                listOf(
                    listOf(-87.719, 41.861), listOf(-87.718, 41.861),
                    listOf(-87.719, 41.862), listOf(-87.719, 41.861),
                ),
            ),
        ),
    )

    private fun route(
        status: RouteStatus = RouteStatus.DRAFT,
        blockId: Int? = 9,
        blockBoundary: GeoJSONPolygon? = boundary,
        innerBoundary: GeoJSONMultiPolygon? = this.innerBoundary,
    ) = Route(
        id = 4,
        code = "NL-R004",
        displayName = "1500 S Millard Ave",
        serviceAreaId = 1,
        blockId = blockId,
        blockBoundary = blockBoundary,
        innerBoundary = innerBoundary,
        status = status,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun startRejectsDuplicate() {
        val started = AdminPanelUiState(routes = listOf(route())).startRouteStatusUpdate(4)
        assertEquals(setOf(4), started?.updatingRouteStatusIds)
        assertNull(started?.startRouteStatusUpdate(4))
    }

    @Test
    fun successUsesServerStatusButPreservesAdminOnlyFields() {
        val before = AdminPanelUiState(routes = listOf(route())).startRouteStatusUpdate(4)!!
        val thin = route(
            status = RouteStatus.PUBLISHED,
            blockId = null,
            blockBoundary = null,
            innerBoundary = null,
        )
        val after = before.completeRouteStatusUpdate(4, thin, "published")
        assertEquals(RouteStatus.PUBLISHED, after.routes.single().status)
        assertEquals(9, after.routes.single().blockId)
        assertEquals(boundary, after.routes.single().blockBoundary)
        assertEquals(innerBoundary, after.routes.single().innerBoundary)
        assertTrue(after.updatingRouteStatusIds.isEmpty())
    }

    @Test
    fun failurePreservesRouteAndUsesScopedError() {
        val original = route()
        val after = AdminPanelUiState(routes = listOf(original)).startRouteStatusUpdate(4)!!
            .failRouteStatusUpdate(4, "HTTP 500")
        assertEquals(original, after.routes.single())
        assertEquals("HTTP 500", after.routeStatusActionError)
        assertNull(after.error)
    }

    // Finding 2: a concurrent success (routeStatusNotice set) must not
    // swallow a sibling route's failure — the snackbar effect prefers
    // routeStatusNotice, so a stale notice hides routeStatusActionError.
    @Test
    fun failureClearsAPreExistingNoticeFromAConcurrentSuccess() {
        val afterConcurrentSuccess = AdminPanelUiState(
            routes = listOf(route()),
            routeStatusNotice = "Route A published",
        )
        val after = afterConcurrentSuccess.failRouteStatusUpdate(4, "HTTP 500")
        assertNull(after.routeStatusNotice)
        assertEquals("HTTP 500", after.routeStatusActionError)
    }

    // Finding 1: a tab refresh (loadTabData's ROUTES branch, which calls
    // applyRoutesRefresh) must recompute from the current state and must
    // never reset updatingRouteStatusIds, routeStatusNotice, or
    // routeStatusActionError — those belong to updateRouteStatus, not to a
    // server-data refresh.
    @Test
    fun tabRefreshCopyPreservesInFlightRouteStatusState() {
        val inFlight = AdminPanelUiState(routes = listOf(route()))
            .startRouteStatusUpdate(7)!!
            .copy(routeStatusNotice = "Route A published") // a sibling update completed concurrently
        val freshRoutes = listOf(route(status = RouteStatus.ARCHIVED))

        val afterRefresh = inFlight.applyRoutesRefresh(freshRoutes)

        assertEquals(setOf(7), afterRefresh.updatingRouteStatusIds)
        assertEquals("Route A published", afterRefresh.routeStatusNotice)
        assertNull(afterRefresh.routeStatusActionError)
        assertEquals(freshRoutes, afterRefresh.routes)
    }
}
