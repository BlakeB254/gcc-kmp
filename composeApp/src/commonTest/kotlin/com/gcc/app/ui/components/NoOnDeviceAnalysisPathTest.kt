package com.gcc.app.ui.components

import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.shared.model.SpotTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the capture flows do when [com.gcc.app.platform.OnDeviceAnalyzer] has no
 * answer (null: analyzer unavailable, or the on-device classifier failed) or an
 * answer that names no trash.
 */
class NoOnDeviceAnalysisPathTest {

    private val here = Coordinates(lng = -87.63, lat = 41.88)

    private val noTrash = OnDeviceAnalysisResult(
        trashTypes = emptyList(), severity = 1, confidence = 0.6f,
        suggestedTags = emptyList(), generatedDescription = "No likely trash detected",
    )
    private val bottles = OnDeviceAnalysisResult(
        trashTypes = listOf("Plastic bottle"), severity = 3, confidence = 0.8f,
        suggestedTags = listOf(SpotTag.TRASH),
    )

    @Test
    fun nullAnalysisFilesNoObservedItem() {
        assertNull(observedItemFromAnalysis(null, here, 90f))
    }

    @Test
    fun analysisNamingNoTrashFilesNoObservedItem() {
        assertNull(observedItemFromAnalysis(noTrash, here, 90f))
    }

    @Test
    fun analysisNamingTrashFilesItsType() {
        val item = assertNotNull(observedItemFromAnalysis(bottles, here, null))
        assertEquals("plastic_bottle", item.itemType)
        assertEquals(here.lat, item.lat) // null heading: stays at the capture point
    }

    private fun analysis(severity: Int, confidence: Float) = OnDeviceAnalysisResult(
        trashTypes = listOf("Can"), severity = severity, confidence = confidence,
        suggestedTags = listOf(SpotTag.TRASH),
    )

    @Test
    fun cleanedSectionNeverAutoConfirmsWhateverTheAnalysisSays() {
        val shapes = buildList {
            add(null)                                   // no analysis
            add(noTrash)                                // no trash labels
            add(bottles)
            for (severity in 1..5) for (confidence in listOf(0.1f, 0.34f, 0.5f, 0.99f)) {
                add(analysis(severity, confidence))     // trash at every severity, low to high confidence
            }
        }
        for (shape in shapes) {
            assertEquals(CaptureState.COMPARING, afterPhotoOutcome(shape).state, "auto-confirmed on $shape")
        }
    }

    @Test
    fun scoreOnlyPrefillsTheSuggestion() {
        assertNull(afterPhotoOutcome(null).suggestedScore)
        assertEquals(1.0f, afterPhotoOutcome(noTrash).suggestedScore)
        assertEquals(0.20f, afterPhotoOutcome(bottles).suggestedScore)
        assertEquals(0.85f, afterPhotoOutcome(analysis(1, 0.9f)).suggestedScore)
    }

    private val photo = byteArrayOf(1, 2, 3)

    @Test
    fun notAnalysedIsLabelledAsSuchNotAsZeroPercent() {
        // Was: `verificationScore ?: 0f` -> "Verification weak 0%".
        val badge = afterPhotoBadge(photo, suggestedScore = null)
        assertEquals(AfterPhotoBadge.NOT_ANALYSED, badge)
        assertEquals("Not analysed", badge.title)
        assertNull(badgePercent(null, badge))
    }

    @Test
    fun confirmWithoutAnalysisSendsNullScoreNotOne() {
        // Was: `verificationScore ?: 1.0f` on Confirm.
        val payload = assertNotNull(confirmPayload(photo, suggestedScore = null))
        assertNull(payload.verificationScore)
        assertEquals(0.2f, confirmPayload(photo, 0.2f)!!.verificationScore)
    }

    @Test
    fun noPhotoCapturedDisablesConfirmAndSaysSo() {
        assertNull(confirmPayload(null, null))
        assertNull(confirmPayload(ByteArray(0), 1.0f))
        val badge = afterPhotoBadge(null, null)
        assertEquals(AfterPhotoBadge.PHOTO_NOT_CAPTURED, badge)
        assertEquals("Photo not captured", badge.title)
        assertNull(badgePercent(1.0f, badge))
        assertEquals(AfterPhotoBadge.PHOTO_NOT_CAPTURED, afterPhotoBadge(ByteArray(0), 1.0f))
    }

    @Test
    fun analysedScoresShowTheirSuggestion() {
        assertEquals(AfterPhotoBadge.LOOKS_CLEAN, afterPhotoBadge(photo, 1.0f))
        assertEquals("100%", badgePercent(1.0f, AfterPhotoBadge.LOOKS_CLEAN))
        assertEquals(AfterPhotoBadge.LITTER_MAY_REMAIN, afterPhotoBadge(photo, 0.2f))
        assertEquals("20%", badgePercent(0.2f, AfterPhotoBadge.LITTER_MAY_REMAIN))
    }
}
