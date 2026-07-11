package com.gcc.app.ui.screens.admin

import com.gcc.shared.model.RouteStatus

internal enum class AdminRouteStatusAction(val label: String, val targetStatus: RouteStatus) {
    PUBLISH("Publish", RouteStatus.PUBLISHED),
    UNPUBLISH("Unpublish", RouteStatus.DRAFT),
    ARCHIVE("Archive", RouteStatus.ARCHIVED),
    RESTORE("Restore to Draft", RouteStatus.DRAFT),
}

internal data class RouteStatusPresentation(
    val label: String,
    val helperText: String,
    val primaryAction: AdminRouteStatusAction,
    val secondaryAction: AdminRouteStatusAction?,
)

internal fun RouteStatus.presentation(): RouteStatusPresentation = when (this) {
    RouteStatus.DRAFT -> RouteStatusPresentation("Draft", "Hidden from workers", AdminRouteStatusAction.PUBLISH, AdminRouteStatusAction.ARCHIVE)
    RouteStatus.PUBLISHED -> RouteStatusPresentation("Published", "Live for workers", AdminRouteStatusAction.UNPUBLISH, AdminRouteStatusAction.ARCHIVE)
    RouteStatus.ARCHIVED -> RouteStatusPresentation("Archived", "Hidden and archived", AdminRouteStatusAction.RESTORE, null)
}

internal fun AdminRouteStatusAction.confirmationCopy(routeName: String): String = when (this) {
    AdminRouteStatusAction.PUBLISH -> "Publish $routeName? It will become live for workers and its eligible linked block will become available."
    AdminRouteStatusAction.UNPUBLISH -> "Return $routeName to draft? It will be hidden from workers. Existing assignments or active jobs are not canceled."
    AdminRouteStatusAction.ARCHIVE -> "Archive $routeName? It will be hidden from workers. Existing assignments or active jobs are not canceled."
    AdminRouteStatusAction.RESTORE -> "Restore $routeName to draft? It will remain hidden until you publish it."
}

internal fun AdminRouteStatusAction.successCopy(routeName: String): String = when (this) {
    AdminRouteStatusAction.PUBLISH -> "$routeName published — live for workers."
    AdminRouteStatusAction.UNPUBLISH -> "$routeName is now a draft — hidden from workers."
    AdminRouteStatusAction.ARCHIVE -> "$routeName archived — hidden from workers."
    AdminRouteStatusAction.RESTORE -> "$routeName restored to draft."
}

internal fun AdminRouteStatusAction.accessibilityLabel(routeName: String): String =
    "$label $routeName"
