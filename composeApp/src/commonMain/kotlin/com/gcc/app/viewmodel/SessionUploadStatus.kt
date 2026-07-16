package com.gcc.app.viewmodel

/**
 * Live progress of the post-scan artifact upload — photos, background capture
 * chunks (video/depth/pose sidecar), and the manifest+event-log bundle —
 * surfaced as a progress card on the session-complete summary so users see
 * their scan actually reach the backend instead of trusting a silent
 * fire-and-forget (87x3ug postmortem: a scan's manifest failed silently and
 * the session stayed invisible to the reconstruction pipeline for hours).
 *
 * Steps are artifact counts: each photo, each not-yet-uploaded capture chunk
 * file, and one step for the manifest bundle (event log + manifest ship
 * together in [com.gcc.app.platform.EnvScanSessionUploader.uploadOrQueue]).
 */
data class SessionUploadStatus(
    val sessionId: String,
    val doneSteps: Int,
    val totalSteps: Int,
    val state: State,
) {
    enum class State {
        /** Artifacts still moving; progress bar shown. */
        UPLOADING,

        /** Everything reached R2 — the DGX pipeline can see the session. */
        DONE,

        /** Something failed or is policy-gated; it sits in a persistent
         *  retry queue that flushes on app activation / next scan / the
         *  in-process retry ladder. The scan is safe locally. */
        QUEUED,
    }

    val fraction: Float
        get() = if (totalSteps <= 0) 0f else doneSteps.toFloat() / totalSteps.toFloat()
}
