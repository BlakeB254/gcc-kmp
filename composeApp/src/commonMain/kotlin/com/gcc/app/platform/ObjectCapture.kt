package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Guided Object Capture stages (mirrors ObjectCaptureSession's lifecycle). */
enum class ObjectCaptureStage {
    UNSUPPORTED,
    INITIALIZING,
    READY,
    DETECTING,
    CAPTURING,
    RECONSTRUCTING,
    COMPLETED,
    FAILED,
}

/** Final output of a capture pass; [usdzPath] is a local file until uploaded. */
data class ObjectCaptureOutcome(
    val usdzPath: String?,
    val widthMeters: Double,
    val heightMeters: Double,
    val depthMeters: Double,
    val volumeLiters: Double,
)

/**
 * Cross-platform face of the guided 3D capture flow (iOS 17 Object Capture +
 * on-device photogrammetry). Swift owns the session/UI/reconstruction behind
 * the registration-pattern bridge (see iosMain `ObjectCaptureBridge.kt`);
 * this controller exposes stage/progress/outcome as flows and forwards the
 * three user actions. Unsupported platforms (Android/Desktop, non-LiDAR
 * iPhones) never show the entry point — gate on [objectCaptureSupported].
 */
expect class ObjectCaptureController() {
    val stage: StateFlow<ObjectCaptureStage>
    /** Reconstruction progress 0..1 while [ObjectCaptureStage.RECONSTRUCTING]. */
    val progress: StateFlow<Double>
    val outcome: StateFlow<ObjectCaptureOutcome?>

    /** Advance the guided flow (ready → detecting → capturing). */
    fun advance()

    /** Done orbiting — start reconstruction. */
    fun finishCapture()

    fun cancel()
}

/** True when the device can run guided Object Capture (LiDAR + A14+, iOS 17). */
expect fun objectCaptureSupported(): Boolean

/** Hosts the native guided-capture view (point cloud, reticle, feedback). */
@Composable
expect fun ObjectCaptureHostView(controller: ObjectCaptureController, modifier: Modifier)

/** Read a local file produced by the capture flow (USDZ upload path). */
expect fun readLocalFileBytes(path: String): ByteArray?

/** Atomically write binary scan evidence to [path], creating parent
 * directories as needed. Used to move completion photos out of RAM and into
 * the same durable retry lifecycle as video/depth capture artifacts. */
expect fun writeLocalFileBytes(path: String, bytes: ByteArray)

/** Delete a local file once its bytes are durably uploaded (capture-chunk
 *  upload path, [com.gcc.app.platform.capture.CaptureChunkUploader]). A
 *  missing file is not an error — never throws. */
expect fun deleteLocalFile(path: String)

/**
 * Present the system AR Quick Look preview for a local USDZ file — pinch,
 * orbit, and place-in-room come free from the OS. No-op off-iOS.
 */
expect fun presentUsdzPreview(path: String)

// ── Disk-backed session journal utils (Pillar 6) ────────────────────────
// Extend-beside-readLocalFileBytes/deleteLocalFile home for the small set of
// file primitives EnvScanSessionRecorder's on-disk journal and the launch-
// time orphan-recovery sweep need. iOS is the only platform that wires these
// to a real Documents path; desktop/android keep pure in-memory behavior
// ([sessionJournalPath] returns null there) but still implement the file ops
// (java.io) so desktop tests can exercise the real journal read/write path.

/**
 * Append one line (a trailing "\n" is added) to the file at [path], creating
 * the file and any missing parent directories on first write. Throws on any
 * I/O failure — [EnvScanSessionRecorder] is the one place that catches it,
 * so a broken journal degrades that recorder to memory-only after exactly
 * one logged failure instead of retrying (and re-logging) on every sample.
 */
expect fun appendLineToFile(path: String, line: String)

/**
 * Truncate-and-rewrite [path] with [lines] (one per line, "\n"-joined) — used
 * to rewrite a journal after a privacy purge drops some of its samples.
 * Throws on failure, same convention as [appendLineToFile].
 */
expect fun writeFileLines(path: String, lines: List<String>)

/**
 * Non-recursive listing of [dirPath]'s immediate children (files and
 * subdirectories) as full paths. Empty when the directory doesn't exist or
 * isn't readable — callers (orphan recovery) treat "nothing to sweep" and
 * "directory missing" identically, so this never throws.
 */
expect fun listFilesInDir(dirPath: String): List<String>

/** Absolute path for [sessionId]'s on-disk journal
 *  (Documents/scan-sessions/{sessionId}.jsonl on iOS), or null where session
 *  journaling isn't wired up (desktop/android — the recorder stays pure
 *  in-memory, matching pre-Pillar-6 behavior). */
expect fun sessionJournalPath(sessionId: String): String?

/** Absolute path for [sessionId]'s on-disk flight-recorder event stream
 *  (Documents/scan-sessions/{sessionId}.events.jsonl on iOS — beside the
 *  session journal so orphan recovery sweeps both in one listing), or null
 *  where session journaling isn't wired up (desktop/android), matching
 *  [sessionJournalPath]. */
expect fun sessionEventLogPath(sessionId: String): String?

/** Directory [sessionJournalPath] entries live under — the launch-recovery
 *  sweep lists this directly rather than re-deriving it from a sessionId. */
expect fun scanSessionsDirPath(): String?

/** Directory each capture session's chunk files live under
 *  (Documents/capture-sessions/{sessionId}/…, see [ArEnvScanOverlay.startCapture])
 *  — swept at launch for chunks that finished writing but never reached
 *  [com.gcc.shared.cache.CaptureChunkStore] (Task 4/5's post-timeout orphan
 *  case). Null on desktop/android, matching [sessionJournalPath]. */
expect fun captureSessionsDirPath(): String?

/** Durable JPEG path for one environment-scan summary photo. Null only on
 * platforms that do not expose a capture-session directory. */
fun sessionPhotoPath(sessionId: String, index: Int): String? =
    captureSessionsDirPath()?.let { "$it/$sessionId/photos/photo-$index.jpg" }
