package com.gcc.app.platform

/** An automatic detection. Never sufficient to file a report (constraint 10). */
const val PROVENANCE_AUTO_DETECTED = "auto_detected"

/** The user tapped it and pressed "Confirm litter". A strong training label. */
const val PROVENANCE_USER_CONFIRMED = "user_confirmed"

/** The user tapped it and pressed "Not litter". A negative training example. */
const val PROVENANCE_USER_REJECTED = "user_rejected"
