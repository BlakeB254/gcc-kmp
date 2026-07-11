package com.gcc.app.viewmodel

import com.gcc.shared.model.Route

internal fun AdminPanelUiState.startRouteStatusUpdate(routeId: Int): AdminPanelUiState? {
    if (routeId in updatingRouteStatusIds) return null
    return copy(
        updatingRouteStatusIds = updatingRouteStatusIds + routeId,
        routeStatusNotice = null,
        routeStatusActionError = null,
    )
}

private fun Route.mergeThinAdminUpdate(updated: Route): Route = updated.copy(
    blockId = updated.blockId ?: blockId,
    blockBoundary = updated.blockBoundary ?: blockBoundary,
    innerBoundary = updated.innerBoundary ?: innerBoundary,
)

internal fun AdminPanelUiState.completeRouteStatusUpdate(
    routeId: Int,
    updated: Route,
    notice: String,
) = copy(
    routes = routes.map { if (it.id == routeId) it.mergeThinAdminUpdate(updated) else it },
    updatingRouteStatusIds = updatingRouteStatusIds - routeId,
    routeStatusNotice = notice,
    routeStatusActionError = null,
)

internal fun AdminPanelUiState.failRouteStatusUpdate(routeId: Int, message: String) = copy(
    updatingRouteStatusIds = updatingRouteStatusIds - routeId,
    routeStatusNotice = null,
    routeStatusActionError = message,
)

/**
 * A routes refresh replaces server data and must never disturb in-flight
 * route-status state (updatingRouteStatusIds, routeStatusNotice, routeStatusActionError).
 */
internal fun AdminPanelUiState.applyRoutesRefresh(freshRoutes: List<Route>) = copy(routes = freshRoutes)
