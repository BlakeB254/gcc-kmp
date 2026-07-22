package com.gcc.app.platform

/** Desktop is the test/dev target — no camera, no capture path. */
actual suspend fun screenLitterPhotoPrivacy(photoBytes: ByteArray): PhotoPrivacyVerdict =
    PhotoPrivacyVerdict.ALLOWED
