package com.gcc.app.platform

/**
 * Pre-upload privacy screen for litter evidence photos (Blake, 2026-07-22):
 * images that reach the public map must never show people, animals, or the
 * interior of a home. Screening runs ON-DEVICE, before any bytes leave the
 * phone — a blocked photo is simply dropped (the marker still files without
 * media) so a privacy hit never blocks the litter report itself.
 *
 * This is the first line of defense; the server keeps a flag-down path
 * (`POST /api/observations/[id]/flag-media`) for anything that slips
 * through, and the DGX privacy scrub handles capture VIDEO separately.
 */
data class PhotoPrivacyVerdict(val allowed: Boolean, val reason: String? = null) {
    companion object {
        val ALLOWED = PhotoPrivacyVerdict(allowed = true)
        fun blocked(reason: String) = PhotoPrivacyVerdict(allowed = false, reason = reason)
    }
}

/**
 * Screens [photoBytes] for privacy-sensitive content. iOS runs Vision
 * (human/animal detection + indoor scene classification); Android/desktop
 * have no on-device detector and allow by default (neither platform captures
 * AR evidence photos today — the iOS actual is the live path).
 */
expect suspend fun screenLitterPhotoPrivacy(photoBytes: ByteArray): PhotoPrivacyVerdict

/**
 * Pure decision half of the iOS screen, split out so the vocabulary and
 * thresholds are unit-testable off-device. Inputs are the platform
 * detectors' outputs; the verdict logic lives here. Two-sided gate
 * (Blake, 2026-07-22): production litter images must show the TRASH and
 * nothing distracting — he saw dog-walk scan photos with the dog in frame
 * and no trash at all.
 *
 * - [humanConfidence]/[animalConfidence]: max detector confidence, or null
 *   when the request returned nothing. Floors are LOW (0.2) on purpose:
 *   for a public-map image a false BLOCK costs one thumbnail, a false
 *   ALLOW publishes a bystander/pet — block on doubt. (Cropping entities
 *   out instead of dropping the photo is the friendlier future version;
 *   v1 keeps the binary gate.)
 * - [sceneLabels]: whole-frame classification, used twice — screened
 *   against [TrashDetectionRules.INDOOR_KEYWORDS] via the shared scene
 *   vote so "kitchen/bedroom/ceiling" frames (home interiors) never
 *   publish, AND required to affirmatively name a trash token
 *   ([GroundExclusionRules.trashTypeFrom] at [TRASH_PRESENCE_CONFIDENCE])
 *   so a frame with no visible trash never publishes either.
 */
fun litterPhotoPrivacyVerdict(
    humanConfidence: Float?,
    animalConfidence: Float?,
    sceneLabels: List<ClassificationObservation>,
): PhotoPrivacyVerdict {
    if ((humanConfidence ?: 0f) >= PRIVACY_DETECTION_CONFIDENCE) {
        return PhotoPrivacyVerdict.blocked("person")
    }
    if ((animalConfidence ?: 0f) >= PRIVACY_DETECTION_CONFIDENCE) {
        return PhotoPrivacyVerdict.blocked("animal")
    }
    if (TrashDetectionRules.sceneVoteFrom(sceneLabels) == SceneVote.INDOOR) {
        return PhotoPrivacyVerdict.blocked("indoor")
    }
    if (GroundExclusionRules.trashTypeFrom(sceneLabels, TRASH_PRESENCE_CONFIDENCE) == null) {
        return PhotoPrivacyVerdict.blocked("no-visible-trash")
    }
    return PhotoPrivacyVerdict.ALLOWED
}

/** See [litterPhotoPrivacyVerdict] — deliberately low; block on doubt. */
const val PRIVACY_DETECTION_CONFIDENCE = 0.2f

/**
 * Presence floor for the visible-trash requirement — deliberately LOWER
 * than [GroundExclusionRules.TRASH_TYPE_CONFIDENCE] (0.30, the autonomous
 * FILING gate): whole-frame classification is weak at naming litter even
 * when it's plainly in frame, so this only asks for a whisper of a trash
 * token; asking for filing-grade confidence here would drop most real
 * evidence photos.
 */
const val TRASH_PRESENCE_CONFIDENCE = 0.05f
