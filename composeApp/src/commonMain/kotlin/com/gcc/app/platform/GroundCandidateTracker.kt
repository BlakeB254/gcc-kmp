package com.gcc.app.platform

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Continuous look-at time (seconds) required to skip classification-wait and promote a PROPOSED candidate straight to SCANNING. Blake's call -- exactly 1.8, not "about 2". */
const val DWELL_PROMOTE_SECONDS = 1.8

/** Max camera-to-candidate distance (meters) for a dwell gaze to count. */
const val DWELL_MAX_METERS = 2.0

/** Max angle (degrees) between cameraForward and the direction to the candidate for a dwell gaze to count. */
const val DWELL_MAX_ANGLE_DEGREES = 8.0

/** Max distance (meters) for a re-observed frame to count as "close" toward scan progress. */
private const val CLOSE_FRAME_MAX_METERS = 2.0

/** Max distance (meters) for [GroundCandidateTracker.recordDwell] to bucket a SCANNING candidate's approach bearing. */
private const val ANGLE_BUCKET_MAX_METERS = 6.0

/** Width (degrees) of one approach-bearing sector; 6 sectors cover the full circle. */
private const val ANGLE_BUCKET_DEGREES = 360.0 / 6

/** Max distance (meters) for [GroundCandidateTracker.adoptTap] to adopt a
 *  candidate as user intent. Widened 1.0 → 1.5 for the build-164 rebuild:
 *  outdoor tap raycasts land with real scatter at 3–4 m, and the 2026-07-25
 *  field walk showed taps directly on visible litter adopting nothing. */
private const val TAP_ADOPT_MAX_METERS = 1.5

/** Seconds of silence (no re-observation) before a SCANNING candidate times out. */
private const val TIMEOUT_SECONDS = 20.0

/** Candidates pulled off the PROPOSED-then-unclassified-SCANNING queue per [GroundCandidateTracker.candidatesAwaitingClassification] call. */
private const val MAX_CLASSIFICATION_REQUESTS_PER_TICK = 2

/** Seconds an appearance-unknown, fully-scanned candidate waits in
 *  AWAITING_INTENT (the "is this litter?" prompt) before it PARKS. 10 s
 *  proved too short in the field (2026-07-25 walk: prompts silently vanished
 *  while the user was still framing the item) — and since the timeout now
 *  parks instead of terminally dismissing, a longer window carries no
 *  trail-the-user cost: the prompt UI clears, the candidate stays
 *  tap-adoptable. */
const val INTENT_PROMPT_TIMEOUT_SECONDS = 25.0

/** Close (<2m) re-observation frames a backdrop-natural candidate needs
 *  before it surfaces at all (reticle or intent prompt). Vegetation/ground-
 *  cover crops match most of any lot — without this attention gate every
 *  weed would draw a reticle. Deliberately equal to
 *  [STEADY_CLOSE_FRAMES_FOR_FULL_ANGLE]: "user is plainly examining it". */
private const val BACKDROP_SURFACE_CLOSE_FRAMES = 8

/** Tracked-candidate count that triggers a terminal-ghost sweep in
 *  [GroundCandidateTracker.ingest] — over a multi-hour walk every
 *  half-metre spatial hash ever proposed otherwise accumulates forever and
 *  every per-tick pass iterates all of them (audit finding, build 165). */
private const val MAX_TRACKED_CANDIDATES = 384

/** Post-sweep target. Eviction order: TIMED_OUT (resurrectable-by-design —
 *  removal just means a fresh proposal on re-observation), then EXCLUDED
 *  (re-vet costs one classify slot), then PARKED (re-proposes fresh; the
 *  prompt may re-ask, acceptable at this scale). COMPLETED and
 *  DISMISSED_UNKNOWN are NEVER swept: their terminality is what prevents a
 *  re-observed item from double-filing or re-prompting an explicit "no". */
private const val TRIM_TARGET_CANDIDATES = 256

/** Tolerance for the "progress >= 1.0" completion gate, to absorb float-sum rounding (0.4f+0.4f+0.2f is not always bit-exact 1.0f). */
private const val PROGRESS_COMPLETE_EPSILON = 0.0005f

/** Close (<2m) re-observation frames that fully substitute for the 3-sector
 *  orbit term in [GroundCandidateTracker]'s progress — see progressOf.
 *  10 → 8 (build 169): at the 750ms tick, 10 frames = 7.5s of standing on
 *  one item before auto-file — field-reported as "focused on it forever,
 *  nothing" (167 run). 8 frames ≈ 6s; the classifier typing gate still
 *  applies. */
private const val STEADY_CLOSE_FRAMES_FOR_FULL_ANGLE = 8f

/** Completion progress threshold for a tap-adopted candidate: explicit user
 *  intent plus decent geometric evidence files without demanding the full
 *  autonomous evidence bar (TestFlight 162: tapped items still never filed
 *  because full progress needed an orbit the user had no reason to walk). */
private const val TAP_ADOPTED_COMPLETE_PROGRESS = 0.6f

private enum class CandidateState {
    PROPOSED, SCANNING, AWAITING_INTENT, EXCLUDED, COMPLETED, TIMED_OUT, DISMISSED_UNKNOWN,

    /** An unanswered "is this litter?" prompt timed out. NOT terminal: the
     *  candidate is invisible (no reticle, no prompt) but keeps accepting
     *  geometry refreshes and remains [GroundCandidateTracker.adoptTap]-able
     *  — walking back and tapping the item resurrects it. Replaces the
     *  pre-164 silent DISMISSED_UNKNOWN reap, which made an ignored prompt
     *  permanently untappable ground. */
    PARKED,
}

private class TrackedCandidate(
    var packet: GroundCandidatePacket,
    var state: CandidateState,
    var lastSeenMs: Long,
    var itemType: String? = null,
    var observations: Int = 1,
    var closeFrames: Int = 0,
    val angleBuckets: MutableSet<Int> = mutableSetOf(),
    /** True once [GroundCandidateTracker.recordClassification] has run for
     *  this id, regardless of outcome (exclude or type). Lets
     *  [GroundCandidateTracker.candidatesAwaitingClassification] tell a
     *  dwell/tap-promoted SCANNING candidate that still needs vetting apart
     *  from one that already went through classification -- see that
     *  method's kdoc. */
    var classified: Boolean = false,
    /** True once [GroundCandidateTracker.adoptTap] or
     *  [GroundCandidateTracker.confirmIntent] claimed this candidate:
     *  explicit user intent, the only thing that lets an appearance-unknown
     *  candidate still complete as [UNKNOWN_LITTER_LABEL] instead of being
     *  unknown-dismissed (TestFlight 157 — see [GroundCandidateTracker.dismissedUnknown]). */
    var adoptedByTap: Boolean = false,
    /** When this candidate entered AWAITING_INTENT — drives the
     *  [INTENT_PROMPT_TIMEOUT_SECONDS] reaper in [GroundCandidateTracker.dismissedUnknown]. */
    var awaitingIntentSinceMs: Long = 0L,
    /** True when classification matched only natural ground cover / vegetation
     *  ([GroundExclusionRules.backdropLabelFrom]). The candidate stays alive
     *  but attention-gated: no reticle and no intent prompt until the user
     *  plainly examines it ([BACKDROP_SURFACE_CLOSE_FRAMES] close frames) or
     *  taps it. This is the build-164 inversion of the old terminal
     *  EXCLUDED-on-"grass" path that silently killed litter whose crop
     *  classified as nothing but its surroundings. */
    var backdropNatural: Boolean = false,
    /** How [itemType] was assigned when the user labelled it via the intent
     *  prompt's chips/"Other…" — "user_chip" | "user_text"; null = classifier
     *  ("auto"). Rides through to [CompletedCandidate.labelSource]: user
     *  labels are the Stage-2 detector's gold training labels. */
    var labelSource: String? = null,
)

/** A candidate whose scan progress reached 1.0 -- ready to file as a real litter item. */
data class CompletedCandidate(
    val id: Long,
    val itemType: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val widthM: Float,
    val heightM: Float,
    val depthM: Float,
    val imageCx: Double,
    val imageCy: Double,
    val imageHalfW: Double,
    val imageHalfH: Double,
    /** True when the user explicitly claimed this candidate (world tap or the
     *  "is this litter?" intent prompt) — the filing site must set
     *  user_confirmed provenance so [hasUserConfirmedLitter] counts it as
     *  report evidence; a purely autonomous completion stays auto_detected. */
    val userIntent: Boolean = false,
    /** "user_chip" | "user_text" when the user assigned the label; null = classifier. */
    val labelSource: String? = null,
)

/**
 * Pure, injectable-clock state machine owning [GroundCandidatePacket]
 * lifecycle: PROPOSED -> SCANNING -> COMPLETED / EXCLUDED / TIMED_OUT. No
 * platform imports anywhere in this file -- every timestamp comes through
 * [nowMs] (or, for [recordDwell], an explicit per-call timestamp), so this
 * runs identically in commonTest, on iOS, and on Android.
 *
 * State transitions, once terminal, are permanent: EXCLUDED, COMPLETED and
 * DISMISSED_UNKNOWN candidates are never revisited by [recordClassification],
 * [recordDwell] or [adoptTap] -- every one of those guards on the candidate's
 * current state before touching it. This is what guarantees an EXCLUDED
 * candidate can never later appear in [activeScanning] or
 * [completedCandidates]. Two deliberate NON-terminal exceptions (build-164
 * rebuild): TIMED_OUT restarts as a fresh PROPOSED on re-observation (ids are
 * spatial hashes — a stable item keeps its id across visits), and PARKED
 * (an ignored intent prompt) stays quiet but tap-adoptable.
 *
 * Constraint: [ingest] packets carry no camera position, so "observation"
 * and "close frame" (distanceM < 2m, the packet's own field) are counted
 * there. Approach-bearing angle buckets DO need a camera position, which
 * only [recordDwell] receives -- so bucketing happens there instead, once
 * per call, against every SCANNING candidate within [ANGLE_BUCKET_MAX_METERS].
 */
class GroundCandidateTracker(val nowMs: () -> Long) {

    private val candidates = LinkedHashMap<Long, TrackedCandidate>()
    private var dwellCandidateId: Long? = null
    private var dwellStartMs: Long? = null

    /** Upsert by id. A brand-new id starts PROPOSED; a re-observation of a
     *  still-active candidate refreshes its geometry/lastSeenMs and counts
     *  toward [progress]'s observation and close-frame terms. Re-observations
     *  of a terminal (EXCLUDED/COMPLETED/TIMED_OUT) id are dropped -- the id
     *  never comes back to life. */
    fun ingest(packets: List<GroundCandidatePacket>) {
        val now = nowMs()
        for (packet in packets) {
            val existing = candidates[packet.id]
            if (existing == null) {
                candidates[packet.id] = TrackedCandidate(
                    packet = packet,
                    state = CandidateState.PROPOSED,
                    lastSeenMs = now,
                    observations = 1,
                )
                continue
            }
            if (existing.state == CandidateState.TIMED_OUT) {
                // Candidate ids are half-metre spatial hashes, so a stable
                // item KEEPS its id across visits. Pre-164, TIMED_OUT was
                // terminal — walk past trash, come back a minute later, and
                // that spot was permanently dead. A fresh observation of a
                // timed-out id restarts it as a brand-new proposal (all
                // evidence reset — the old counters are stale by definition).
                candidates[packet.id] = TrackedCandidate(
                    packet = packet,
                    state = CandidateState.PROPOSED,
                    lastSeenMs = now,
                    observations = 1,
                )
                continue
            }
            if (existing.state != CandidateState.PROPOSED &&
                existing.state != CandidateState.SCANNING &&
                existing.state != CandidateState.PARKED
            ) {
                continue // terminal -- ignore, never resurrect.
            }
            // PARKED refreshes geometry/recency only — it must stay accurate
            // for a later adoptTap — but stays PARKED (no state change here).
            existing.packet = packet
            existing.lastSeenMs = now
            existing.observations += 1
            if (packet.distanceM < CLOSE_FRAME_MAX_METERS) existing.closeFrames += 1
        }
        trimIfNeeded()
    }

    /** Bound the map over long sessions — see [MAX_TRACKED_CANDIDATES] /
     *  [TRIM_TARGET_CANDIDATES] for the policy and what is never swept. */
    private fun trimIfNeeded() {
        if (candidates.size <= MAX_TRACKED_CANDIDATES) return
        for (sweepState in arrayOf(CandidateState.TIMED_OUT, CandidateState.EXCLUDED, CandidateState.PARKED)) {
            val iterator = candidates.entries.iterator()
            while (iterator.hasNext() && candidates.size > TRIM_TARGET_CANDIDATES) {
                if (iterator.next().value.state == sweepState) iterator.remove()
            }
            if (candidates.size <= TRIM_TARGET_CANDIDATES) return
        }
    }

    /**
     * Candidates still needing a classification result, oldest first WITHIN
     * each of two buckets and capped at [MAX_CLASSIFICATION_REQUESTS_PER_TICK]
     * total per call: PROPOSED candidates first (the common case), then any
     * unclassified SCANNING candidate -- one promoted straight to SCANNING by
     * [recordDwell] or [adoptTap] before classification ever reached it, so
     * it was never exclusion-vetted. Without this second bucket a dwell-
     * promoted pole/bench base would never get vetted and would file as
     * [UNKNOWN_LITTER_LABEL] forever instead of being retro-excluded via
     * [recordClassification]. A candidate that HAS already been classified
     * (the [TrackedCandidate.classified] flag [recordClassification] sets)
     * never reappears here, whatever its resulting state.
     */
    fun candidatesAwaitingClassification(): List<GroundCandidatePacket> {
        val proposed = candidates.values.asSequence()
            .filter { it.state == CandidateState.PROPOSED }
        val scanningUnclassified = candidates.values.asSequence()
            .filter { it.state == CandidateState.SCANNING && !it.classified }
        return (proposed + scanningUnclassified)
            .take(MAX_CLASSIFICATION_REQUESTS_PER_TICK)
            .map { it.packet }
            .toList()
    }

    /** A classification result for [id]. A non-null [exclusionLabel] always
     *  wins (silent, terminal EXCLUDED) even if the candidate was already
     *  promoted to SCANNING by dwell/tap -- this is the "retro-exclude" path.
     *  Otherwise the candidate becomes/stays SCANNING. [itemType] is set only
     *  if it isn't already AND [trashType] actually named something: a
     *  no-match classification leaves itemType null on purpose (TestFlight
     *  157) -- "the classifier recognized nothing" is NOT evidence of litter,
     *  and an untyped candidate is unknown-dismissed at full progress unless
     *  the user tapped it (see [dismissedUnknown]/[completedCandidates]).
     *  Calling this twice with two different [trashType]s never re-types an
     *  already-typed candidate. Sets [TrackedCandidate.classified]
     *  unconditionally so [candidatesAwaitingClassification] stops offering
     *  the id up, whichever branch runs. Unknown or already-terminal ids are
     *  ignored (and leave classified untouched). */
    fun recordClassification(
        id: Long,
        exclusionLabel: String?,
        trashType: String?,
        backdropLabel: String? = null,
    ) {
        val candidate = candidates[id] ?: return
        if (candidate.state != CandidateState.PROPOSED && candidate.state != CandidateState.SCANNING) return
        candidate.classified = true
        if (exclusionLabel != null) {
            candidate.state = CandidateState.EXCLUDED
        } else {
            candidate.state = CandidateState.SCANNING
            if (candidate.itemType == null && trashType != null) candidate.itemType = trashType
            // Backdrop-natural is one-way sticky within a session of
            // classifications: a later no-match pass (labels drift tick to
            // tick) must not promote a weed to full visibility. A trash type
            // clears it — the crop affirmatively named an item.
            if (backdropLabel != null && candidate.itemType == null) candidate.backdropNatural = true
            if (candidate.itemType != null) candidate.backdropNatural = false
        }
    }

    /** True while [id] is attention-gated: backdrop-natural, untapped, and
     *  not yet examined up close ([BACKDROP_SURFACE_CLOSE_FRAMES]). Quiet
     *  candidates get no reticle and no intent prompt — but keep accruing
     *  evidence and stay tap-adoptable. */
    private fun isBackdropQuiet(candidate: TrackedCandidate): Boolean =
        candidate.backdropNatural &&
            !candidate.adoptedByTap &&
            candidate.closeFrames < BACKDROP_SURFACE_CLOSE_FRAMES

    /**
     * One frame of gaze input. Two independent things happen here, both
     * gated on distance/state but NOT on each other:
     *
     * 1. Dwell promotion: among PROPOSED candidates within [DWELL_MAX_METERS]
     *    and [DWELL_MAX_ANGLE_DEGREES] of [cameraForward], the nearest one is
     *    "gazed at". Dwell time is continuous -- it only accumulates while
     *    the SAME candidate keeps qualifying on consecutive calls; a call
     *    where no candidate qualifies, or a different one does, resets the
     *    accumulator to start fresh on whatever (if anything) qualifies now.
     *    At/above [DWELL_PROMOTE_SECONDS] the gazed candidate skips
     *    classification-wait straight to SCANNING.
     * 2. Angle-bucket accrual (see the class doc's constraint comment): every
     *    SCANNING candidate within [ANGLE_BUCKET_MAX_METERS] gets the current
     *    approach-bearing sector added to its bucket set, regardless of
     *    whether it is the gaze target.
     *
     * [frameNowMs] is the AR frame's own timestamp, passed explicitly per
     * call — deliberately NOT read from the injected [nowMs] clock property,
     * because dwell accumulation must be frame-precise and the two clocks
     * are allowed to disagree. Callers must keep it monotonic across calls.
     */
    fun recordDwell(cameraPos: FloatArray, cameraForward: FloatArray, frameNowMs: Long) {
        val camX = cameraPos[0]
        val camY = cameraPos[1]
        val camZ = cameraPos[2]

        var gazedId: Long? = null
        var gazedDistance = Float.MAX_VALUE
        for ((id, candidate) in candidates) {
            if (candidate.state != CandidateState.PROPOSED) continue
            val p = candidate.packet
            val distance = distance3D(p.x, p.y, p.z, camX, camY, camZ)
            if (distance > DWELL_MAX_METERS) continue
            val angle = angleBetweenDegrees(
                cameraForward[0], cameraForward[1], cameraForward[2],
                p.x - camX, p.y - camY, p.z - camZ,
            )
            if (angle > DWELL_MAX_ANGLE_DEGREES) continue
            if (distance < gazedDistance) {
                gazedDistance = distance
                gazedId = id
            }
        }

        if (gazedId != null) {
            if (dwellCandidateId != gazedId) {
                dwellCandidateId = gazedId
                dwellStartMs = frameNowMs
            }
            val elapsedSeconds = (frameNowMs - (dwellStartMs ?: frameNowMs)) / 1000.0
            if (elapsedSeconds >= DWELL_PROMOTE_SECONDS) {
                candidates[gazedId]?.let { candidate ->
                    if (candidate.state == CandidateState.PROPOSED) candidate.state = CandidateState.SCANNING
                }
            }
        } else {
            dwellCandidateId = null
            dwellStartMs = null
        }

        for (candidate in candidates.values) {
            if (candidate.state != CandidateState.SCANNING) continue
            val p = candidate.packet
            if (distance3D(p.x, p.y, p.z, camX, camY, camZ) > ANGLE_BUCKET_MAX_METERS) continue
            candidate.angleBuckets.add(bearingBucket(fromX = p.x, fromZ = p.z, toX = camX, toZ = camZ))
        }
    }

    /** A user tap at a world point immediately promotes the nearest non-terminal candidate within [TAP_ADOPT_MAX_METERS] to SCANNING (user intent is authoritative), returning its id -- or null if nothing is close enough. AWAITING_INTENT candidates qualify too: a world tap near the prompted item IS the answer the prompt is asking for. */
    fun adoptTap(worldX: Float, worldY: Float, worldZ: Float): Long? {
        var bestId: Long? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, candidate) in candidates) {
            if (candidate.state != CandidateState.PROPOSED &&
                candidate.state != CandidateState.SCANNING &&
                candidate.state != CandidateState.AWAITING_INTENT &&
                candidate.state != CandidateState.PARKED
            ) continue
            val p = candidate.packet
            val distance = distance3D(p.x, p.y, p.z, worldX, worldY, worldZ)
            if (distance <= TAP_ADOPT_MAX_METERS && distance < bestDistance) {
                bestDistance = distance
                bestId = id
            }
        }
        bestId?.let {
            candidates[it]?.apply {
                state = CandidateState.SCANNING
                adoptedByTap = true
            }
        }
        return bestId
    }

    /** Scan evidence 0..1 for candidate [id] (0 if unknown/never observed). See [progressOf] for the weighting. */
    fun progress(id: Long): Float = candidates[id]?.let(::progressOf) ?: 0f

    /** SCANNING candidates whose [progress] reached 1.0 AND that carry filing
     *  evidence — a positive trash-type classification, or explicit user
     *  intent ([adoptTap]) — transitioned to terminal COMPLETED and returned.
     *  Emit-once: a candidate returned here never appears again from any
     *  query method.
     *
     *  Appearance gating (TestFlight 157): geometry alone must never file.
     *  An untyped, untapped candidate at full progress is either HELD (still
     *  SCANNING, classification hasn't run yet — the awaiting-classification
     *  queue will get to it, or the 20s timeout reaps it) or, once
     *  classification ran and named nothing, unknown-dismissed via
     *  [dismissedUnknown]. Only a tap lets an untyped candidate through, as
     *  [UNKNOWN_LITTER_LABEL] (intent overrides). */
    fun completedCandidates(): List<CompletedCandidate> {
        val result = mutableListOf<CompletedCandidate>()
        for (candidate in candidates.values) {
            if (candidate.state != CandidateState.SCANNING) continue
            // Explicit user intent (tap / intent-prompt answer) lowers the
            // completion bar — the user has already vouched for the item.
            val threshold =
                if (candidate.adoptedByTap) TAP_ADOPTED_COMPLETE_PROGRESS
                else 1.0f - PROGRESS_COMPLETE_EPSILON
            if (progressOf(candidate) < threshold) continue
            if (candidate.itemType == null && !candidate.adoptedByTap) continue
            candidate.state = CandidateState.COMPLETED
            val p = candidate.packet
            result.add(
                CompletedCandidate(
                    id = p.id,
                    itemType = candidate.itemType ?: UNKNOWN_LITTER_LABEL,
                    x = p.x, y = p.y, z = p.z,
                    widthM = p.widthM, heightM = p.heightM, depthM = p.depthM,
                    imageCx = p.imageCx, imageCy = p.imageCy,
                    imageHalfW = p.imageHalfW, imageHalfH = p.imageHalfH,
                    userIntent = candidate.adoptedByTap,
                    labelSource = candidate.labelSource,
                ),
            )
        }
        return result
    }

    /** Appearance-unknown handling, two stages (TestFlight 157 → 158 UX fix):
     *
     *  Stage 1 — a SCANNING candidate at full [progress] whose classification
     *  RAN and named neither a fixture nor a trash type, untapped, moves to
     *  AWAITING_INTENT instead of being silently discarded: the user just
     *  watched the reticle's fill bar complete, so "…and then nothing" read
     *  as a broken scanner in the field. The caller surfaces those ids
     *  (via [awaitingIntent]) as an "is this litter?" prompt — geometry
     *  still never files on its own, but the user's answer is one tap away.
     *
     *  Stage 2 — an AWAITING_INTENT candidate the user ignored for
     *  [INTENT_PROMPT_TIMEOUT_SECONDS] transitions to terminal
     *  DISMISSED_UNKNOWN and is returned so the caller clears the reticle.
     *  Emit-once for the RETURNED ids, same contract as
     *  [completedCandidates]; mutually exclusive with it by construction
     *  (typed-or-tapped completes, this path requires untyped AND untapped
     *  AND classified). */
    fun dismissedUnknown(): List<Long> {
        val now = nowMs()
        val result = mutableListOf<Long>()
        for (candidate in candidates.values) {
            when (candidate.state) {
                CandidateState.SCANNING -> {
                    if (!candidate.classified || candidate.itemType != null || candidate.adoptedByTap) continue
                    if (progressOf(candidate) < 1.0f - PROGRESS_COMPLETE_EPSILON) continue
                    // A backdrop-natural candidate must additionally have
                    // been examined up close before it may ASK — otherwise
                    // an orbit past a weed patch would prompt on every tuft.
                    if (isBackdropQuiet(candidate)) continue
                    candidate.state = CandidateState.AWAITING_INTENT
                    candidate.awaitingIntentSinceMs = now
                }
                CandidateState.AWAITING_INTENT -> {
                    if (now - candidate.awaitingIntentSinceMs < INTENT_PROMPT_TIMEOUT_SECONDS * 1000) continue
                    // Unanswered ≠ answered "no": PARK instead of terminal
                    // dismissal. The id is still returned so the caller
                    // clears the reticle/prompt — but a later world tap on
                    // the same spot resurrects the candidate via [adoptTap].
                    candidate.state = CandidateState.PARKED
                    result.add(candidate.packet.id)
                }
                else -> continue
            }
        }
        return result
    }

    /** Ids currently AWAITING_INTENT (fully scanned, appearance-unknown,
     *  waiting on the "is this litter?" prompt). Stable across calls — NOT
     *  emit-once — so the caller can keep one prompt mounted until the user
     *  answers or the [INTENT_PROMPT_TIMEOUT_SECONDS] reaper fires. */
    fun awaitingIntent(): List<Long> =
        candidates.values
            .filter { it.state == CandidateState.AWAITING_INTENT }
            .map { it.packet.id }

    /** The user answered "yes, it's litter" on an AWAITING_INTENT prompt:
     *  marks explicit intent and returns the candidate to SCANNING, where the
     *  next [completedCandidates] sweep completes it (full progress + intent)
     *  as [UNKNOWN_LITTER_LABEL] with [CompletedCandidate.userIntent] set.
     *  False when [id] is not awaiting intent (already reaped or answered). */
    fun confirmIntent(id: Long, itemType: String? = null, labelSource: String? = null): Boolean {
        val candidate = candidates[id] ?: return false
        if (candidate.state != CandidateState.AWAITING_INTENT) return false
        candidate.adoptedByTap = true
        candidate.state = CandidateState.SCANNING
        // Manual label from the prompt's chips / "Other…" — an intent
        // candidate is by definition untyped, so the user's word IS the type
        // (and a gold training label for Stage 2).
        if (itemType != null) {
            candidate.itemType = itemType
            candidate.labelSource = labelSource
            candidate.backdropNatural = false
        }
        return true
    }

    /** The user answered "not litter" on an AWAITING_INTENT prompt: terminal
     *  DISMISSED_UNKNOWN immediately (the caller clears the reticle itself —
     *  this id is NOT re-emitted by [dismissedUnknown]). False when [id] is
     *  not awaiting intent. */
    fun rejectIntent(id: Long): Boolean {
        val candidate = candidates[id] ?: return false
        if (candidate.state != CandidateState.AWAITING_INTENT) return false
        candidate.state = CandidateState.DISMISSED_UNKNOWN
        return true
    }

    /** SCANNING candidates not re-observed for [TIMEOUT_SECONDS], transitioned to terminal TIMED_OUT and returned as ids. Emit-once, same contract as [completedCandidates]. */
    fun timedOut(): List<Long> {
        val now = nowMs()
        val result = mutableListOf<Long>()
        for (candidate in candidates.values) {
            if (candidate.state != CandidateState.SCANNING) continue
            if (now - candidate.lastSeenMs < TIMEOUT_SECONDS * 1000) continue
            candidate.state = CandidateState.TIMED_OUT
            result.add(candidate.packet.id)
        }
        return result
    }

    /** (id, progress) for every currently-SCANNING or AWAITING_INTENT
     *  candidate, for reticle rendering — an intent-prompted candidate keeps
     *  its (full) reticle on screen so the "is this litter?" prompt has a
     *  visible anchor instead of asking about something that just vanished. */
    fun activeScanning(): List<Pair<Long, Float>> =
        candidates.values
            .filter { it.state == CandidateState.SCANNING || it.state == CandidateState.AWAITING_INTENT }
            .filterNot(::isBackdropQuiet)
            .map { it.packet.id to progressOf(it) }

    private fun progressOf(candidate: TrackedCandidate): Float {
        // The orbit term (3 of 6 approach-bearing sectors) rewards walking
        // around the item, but it must not be REQUIRED: field report
        // (TestFlight 162) — a worker standing over a clear piece of trash,
        // camera held on it, watched the reticle stall forever, because a
        // single-bearing approach caps the old formula at 0.73. Sustained
        // close observation ([STEADY_CLOSE_FRAMES_FOR_FULL_ANGLE] frames
        // under 2m) is comparable evidence that the item was really examined,
        // so it substitutes for the orbit — orbiting stays the FAST path
        // (3 sectors beat 10 close frames), standing and scanning the
        // patient one.
        val orbitTerm = min(candidate.angleBuckets.size / 3f, 1f)
        val steadyTerm = min(candidate.closeFrames / STEADY_CLOSE_FRAMES_FOR_FULL_ANGLE, 1f)
        val angleTerm = max(orbitTerm, steadyTerm)
        val closeTerm = min(candidate.closeFrames / 4f, 1f)
        // 6 → 4 observations for a full term (build 169): with the other
        // terms already requiring sustained close attention, demanding 4.5s
        // of raw sightings on top made auto-file feel dead in the field.
        val observationTerm = min(candidate.observations / 4f, 1f)
        return 0.4f * angleTerm + 0.4f * closeTerm + 0.2f * observationTerm
    }
}

private fun distance3D(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    val dz = az - bz
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Angle (degrees, 0..180) between two 3D vectors. A zero-length vector is degenerate and reports 180 (never within a dwell gate). */
private fun angleBetweenDegrees(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Double {
    val magA = sqrt((ax * ax + ay * ay + az * az).toDouble())
    val magB = sqrt((bx * bx + by * by + bz * bz).toDouble())
    if (magA == 0.0 || magB == 0.0) return 180.0
    val dot = (ax * bx + ay * by + az * bz).toDouble()
    val cosine = (dot / (magA * magB)).coerceIn(-1.0, 1.0)
    return acos(cosine) * (180.0 / PI)
}

/** Which of 6 60-degree sectors the bearing from (fromX, fromZ) to (toX, toZ) falls in, in the ground (XZ) plane. */
private fun bearingBucket(fromX: Float, fromZ: Float, toX: Float, toZ: Float): Int {
    val dx = (toX - fromX).toDouble()
    val dz = (toZ - fromZ).toDouble()
    var degrees = atan2(dx, dz) * (180.0 / PI)
    if (degrees < 0) degrees += 360.0
    return (degrees / ANGLE_BUCKET_DEGREES).toInt().coerceIn(0, 5)
}
