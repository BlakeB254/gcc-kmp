package com.gcc.app.platform

import com.gcc.shared.cache.CaptureChunkRecord
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.app.platform.capture.CaptureChunkUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.mp.KoinPlatform

/**
 * Books finished capture chunks into [CaptureChunkStore] and kicks
 * [CaptureChunkUploader] best-effort. Shared by BOTH AR overlays'
 * capture paths ([ArEnvScanOverlay] and [ArRouteOverlay]) so the env-scan
 * and route-lap recorders can never drift in their bookkeeping.
 *
 * Reached through Koin's global context (`KoinPlatform`) — the overlays are
 * plain classes instantiated by `remember { }` in their screens, outside the
 * Koin graph. Every failure is caught: chunk bookkeeping must never crash a
 * scan; worst case an unrecorded chunk waits for the next drain or the
 * launch-time orphan sweep.
 */
internal fun bookCaptureChunksAsync(
    scope: CoroutineScope,
    sessionId: String,
    cellId: String,
    infos: List<CaptureChunkInfo>,
) {
    scope.launch {
        try {
            val koin = KoinPlatform.getKoin()
            val store = koin.getOrNull<CaptureChunkStore>() ?: return@launch
            val nowMs = Clock.System.now().toEpochMilliseconds()
            for (info in infos) {
                store.insertChunk(
                    CaptureChunkRecord(
                        sessionId = sessionId,
                        chunkIndex = info.index,
                        kind = info.kind,
                        filePath = info.filePath,
                        sizeBytes = info.sizeBytes,
                        locationCellId = cellId,
                        r2Key = null,
                        uploadedAtMs = null,
                        createdAtMs = nowMs,
                    )
                )
            }
            // Flight-recorder breadcrumb: field session env-1784734348036
            // uploaded sidecar+depth but never the video, and nothing in
            // events.jsonl said which kinds were ever booked — this line is
            // the audit trail that distinguishes "never finalized/drained"
            // from "booked but upload failed".
            DiagnosticsLog.info(
                "capture",
                "booked ${infos.size} chunk artifact(s) for $sessionId: " +
                    infos.joinToString { "${it.index}:${it.kind}(${it.sizeBytes}B)" },
            )
            koin.getOrNull<CaptureChunkUploader>()?.flushPending()
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "chunk bookkeeping failed for $sessionId (${infos.size} chunk(s))", e)
        }
    }
}
