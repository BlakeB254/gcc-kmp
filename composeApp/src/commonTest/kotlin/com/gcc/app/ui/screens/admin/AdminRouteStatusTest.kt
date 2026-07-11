package com.gcc.app.ui.screens.admin

import com.gcc.shared.model.RouteStatus
import com.gcc.shared.model.UpdateRouteStatusRequest
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminRouteStatusTest {
    @Test fun draftOffersPublishAndArchive() {
        val p = RouteStatus.DRAFT.presentation()
        assertEquals("Draft", p.label)
        assertEquals("Hidden from workers", p.helperText)
        assertEquals(AdminRouteStatusAction.PUBLISH, p.primaryAction)
        assertEquals(AdminRouteStatusAction.ARCHIVE, p.secondaryAction)
    }

    @Test fun publishedOffersUnpublishAndArchive() {
        val p = RouteStatus.PUBLISHED.presentation()
        assertEquals("Published", p.label)
        assertEquals("Live for workers", p.helperText)
        assertEquals(AdminRouteStatusAction.UNPUBLISH, p.primaryAction)
        assertEquals(AdminRouteStatusAction.ARCHIVE, p.secondaryAction)
    }

    @Test fun archivedOffersRestoreOnly() {
        val p = RouteStatus.ARCHIVED.presentation()
        assertEquals("Archived", p.label)
        assertEquals("Hidden and archived", p.helperText)
        assertEquals(AdminRouteStatusAction.RESTORE, p.primaryAction)
        assertNull(p.secondaryAction)
    }

    @Test fun actionTargetsAndCopyAreExplicit() {
        assertEquals(RouteStatus.PUBLISHED, AdminRouteStatusAction.PUBLISH.targetStatus)
        assertEquals(RouteStatus.DRAFT, AdminRouteStatusAction.UNPUBLISH.targetStatus)
        assertEquals(RouteStatus.ARCHIVED, AdminRouteStatusAction.ARCHIVE.targetStatus)
        assertEquals(RouteStatus.DRAFT, AdminRouteStatusAction.RESTORE.targetStatus)
        assertEquals(
            "Publish 1500 S Millard Ave? It will become live for workers and its eligible linked block will become available.",
            AdminRouteStatusAction.PUBLISH.confirmationCopy("1500 S Millard Ave"),
        )
    }

    @Test fun actionAccessibilityLabelIncludesRouteName() {
        assertEquals(
            "Publish 1500 S Millard Ave",
            AdminRouteStatusAction.PUBLISH.accessibilityLabel("1500 S Millard Ave"),
        )
    }

    @Test fun requestUsesRouteStatusSerialNames() {
        assertEquals("{\"status\":\"draft\"}", GccJson.encodeToString(UpdateRouteStatusRequest(RouteStatus.DRAFT)))
        assertEquals("{\"status\":\"published\"}", GccJson.encodeToString(UpdateRouteStatusRequest(RouteStatus.PUBLISHED)))
        assertEquals("{\"status\":\"archived\"}", GccJson.encodeToString(UpdateRouteStatusRequest(RouteStatus.ARCHIVED)))
    }
}
