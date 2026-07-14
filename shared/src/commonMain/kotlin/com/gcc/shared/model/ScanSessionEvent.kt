package com.gcc.shared.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of a scan session's flight-recorder event stream
 * (`…/events.jsonl.gz`, scan-session-replay PRD Phase 1) — a sparse,
 * flat envelope so every event type shares one schema and unused fields
 * cost zero bytes on the wire (see [ScanEventJson]).
 *
 * Written by the client's SessionEventLog (composeApp) and parsed by the
 * backend indexer's headline-metrics extractor; both sides live off this
 * one @Serializable class so the wire format can't drift.
 *
 * `type` vocabulary (eventLogSchema 1):
 *  - `caption`   — instruction/status text shown to the worker (`msg`)
 *  - `tap`       — screen tap seeking a litter candidate; `state` =
 *                  "suppressed" when the auto-lock confirm card was already
 *                  up (tap never reached the tap-candidate state machine)
 *  - `pill`      — tap-candidate pill phase transition (`state` =
 *                  LOCATING/CHECKING/AWAITING_CONFIRMATION/NO_RAYCAST/IDLE;
 *                  `label`/`confidence`/`distanceM` when classified)
 *  - `confirm` / `reject` — explicit user verdict on a candidate (`label`)
 *  - `detection` — ground-candidate classification (`label`, `confidence`,
 *                  `distanceM`; `source` = geometry/dwell/tap/vision names
 *                  which intent path produced it; `outcome` =
 *                  accepted/excluded:<label>/timeout is the verdict)
 *  - `gate`      — outdoor-gate state change (`state`, cause in `msg`)
 *  - `coverage`  — coverage milestone crossed (`pct` = 25/50/75/95)
 *  - `lap`       — block-route lap transition (`state` = baseline/clean/…)
 *  - `upload`    — upload attempt outcome (`tag` = artifact kind, `ok`,
 *                  detail in `msg`, R2 key in `key`)
 *  - `diag`      — DiagnosticsLog entry (`state` = INFO/ERROR, `tag`, `msg`)
 *  - `truncated` — synthetic final marker when the cap dropped events
 *                  (`pct` = dropped count)
 */
@Serializable
data class ScanSessionEvent(
    /** Milliseconds since session start (session-relative, never epoch). */
    val t: Long,
    val type: String,
    val msg: String? = null,
    val label: String? = null,
    val confidence: Float? = null,
    val distanceM: Double? = null,
    val state: String? = null,
    val tag: String? = null,
    val pct: Int? = null,
    val ok: Boolean? = null,
    val key: String? = null,
    val source: String? = null,
    val outcome: String? = null,
)

/**
 * Compact wire config for [ScanSessionEvent] lines. Deliberately NOT
 * [com.gcc.shared.serialization.GccJson]: that instance sets
 * `encodeDefaults = true`, which would serialize every null field of this
 * sparse envelope and roughly triple each line — the event log has a
 * <200KB-gzipped budget per session.
 */
val ScanEventJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}
