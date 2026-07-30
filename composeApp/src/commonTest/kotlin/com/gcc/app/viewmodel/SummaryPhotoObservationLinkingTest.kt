package com.gcc.app.viewmodel

import com.gcc.shared.model.ObservedSpotItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SummaryPhotoObservationLinkingTest {
    @Test
    fun `summary photos link one-to-one to nearest litter timestamps`() {
        val items = listOf(
            item(atMs = 1_000),
            item(atMs = 2_000),
            item(atMs = 3_000),
        )

        val linked = assignSummaryPhotoUrls(
            items,
            listOf(
                2_100L to "https://blob/photo-1.jpg",
                2_900L to "https://blob/photo-2.jpg",
            ),
        )

        assertEquals("https://blob/photo-1.jpg", linked[2_000])
        assertEquals("https://blob/photo-2.jpg", linked[3_000])
        assertFalse(1_000L in linked)
    }

    @Test
    fun `exact evidence and landmark rows are not summary-photo targets`() {
        val items = listOf(
            item(atMs = 1_000, mediaUrl = "https://blob/exact.jpg"),
            item(atMs = 1_100, domain = "vegetation"),
            item(atMs = 1_200),
        )

        val linked = assignSummaryPhotoUrls(
            items,
            listOf(1_050L to "https://blob/summary.jpg"),
        )

        assertEquals(mapOf(1_200L to "https://blob/summary.jpg"), linked)
    }

    private fun item(
        atMs: Long,
        mediaUrl: String? = null,
        domain: String? = null,
    ) = ObservedSpotItem(
        lat = 41.86,
        lng = -87.70,
        itemType = "other",
        atMs = atMs,
        mediaUrl = mediaUrl,
        domain = domain,
    )
}
