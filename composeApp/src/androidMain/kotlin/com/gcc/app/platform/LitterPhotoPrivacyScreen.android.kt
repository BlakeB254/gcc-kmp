package com.gcc.app.platform

/** No on-device detector wired on Android yet; AR evidence photos are an
 *  iOS-only capture path today, so this actual is unreachable in practice.
 *  If Android ever captures map-bound litter photos, wire ML Kit here —
 *  do NOT ship allow-all into a live capture path. */
actual suspend fun screenLitterPhotoPrivacy(photoBytes: ByteArray): PhotoPrivacyVerdict =
    PhotoPrivacyVerdict.ALLOWED
