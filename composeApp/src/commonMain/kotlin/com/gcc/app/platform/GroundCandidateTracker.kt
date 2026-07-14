package com.gcc.app.platform

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
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

/** Max distance (meters) for [GroundCandidateTracker.adoptTap] to adopt a candidate as user intent. */
private const val TAP_ADOPT_MAX_METERS = 1.0

/** Seconds of silence (no re-observation) before a SCANNING candidate times out. */
private const val TIMEOUT_SECONDS = 20.0

/** Candidates pulled off the PROPOSED-then-unclassified-SCANNING queue per [GroundCandidateTracker.candidatesAwaitingClassification] call. */
private const val MAX_CLASSIFICATION_REQUESTS_PER_TICK = 2

/** Tolerance for the "progress >= 1.0" completion gate, to absorb float-sum rounding (0.4f+0.4f+0.2f is not always bit-exact 1.0f). */
private const val PROGRESS_COMPLETE_EPSILON = 0.0005f

private enum class CandidateState { PROPOSED, SCANNING, EXCLUDED, COMPLETED, TIMED_OUT }

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
)

/**
 * Pure, injectable-clock state machine owning [GroundCandidatePacket]
 * lifecycle: PROPOSED -> SCANNING -> COMPLETED / EXCLUDED / TIMED_OUT. No
 * platform imports anywhere in this file -- every timestamp comes through
 * [nowMs] (or, for [recordDwell], an explicit per-call timestamp), so this
 * runs identically in commonTest, on iOS, and on Android.
 *
 * State transitions, once terminal, are permanent: EXCLUDED, COMPLETED and
 * TIMED_OUT candidates are never revisited by [ingest], [recordClassification],
 * [recordDwell] or [adoptTap] -- every one of those guards on the candidate's
 * current state before touching it. This is what guarantees an EXCLUDED
 * candidate can never later appear in [activeScanning] or [completedCandidates].
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
            if (existing.state != CandidateState.PROPOSED && existing.state != CandidateState.SCANNING) {
                continue // terminal -- ignore, never resurrect.
            }
            existing.packet = packet
            existing.lastSeenMs = now
            existing.observations += 1
            if (packet.distanceM < CLOSE_FRAME_MAX_METERS) existing.closeFrames += 1
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
     *  if it isn't already: a dwell/tap-promoted candidate (itemType still
     *  null) gets typed by this call; a candidate classified the ordinary way
     *  (PROPOSED, itemType already set by an earlier call) keeps its type --
     *  this also means calling this twice with two different [trashType]s
     *  never re-types an already-typed candidate. Sets [TrackedCandidate.classified]
     *  unconditionally so [candidatesAwaitingClassification] stops offering
     *  the id up, whichever branch runs. Unknown or already-terminal ids are
     *  ignored (and leave classified untouched). */
    fun recordClassification(id: Long, exclusionLabel: String?, trashType: String?) {
        val candidate = candidates[id] ?: return
        if (candidate.state != CandidateState.PROPOSED && candidate.state != CandidateState.SCANNING) return
        candidate.classified = true
        if (exclusionLabel != null) {
            candidate.state = CandidateState.EXCLUDED
        } else {
            candidate.state = CandidateState.SCANNING
            if (candidate.itemType == null) candidate.itemType = trashType ?: UNKNOWN_LITTER_LABEL
        }
    }

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

    /** A user tap at a world point immediately promotes the nearest non-terminal candidate within [TAP_ADOPT_MAX_METERS] to SCANNING (user intent is authoritative), returning its id -- or null if nothing is close enough. */
    fun adoptTap(worldX: Float, worldY: Float, worldZ: Float): Long? {
        var bestId: Long? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, candidate) in candidates) {
            if (candidate.state != CandidateState.PROPOSED && candidate.state != CandidateState.SCANNING) continue
            val p = candidate.packet
            val distance = distance3D(p.x, p.y, p.z, worldX, worldY, worldZ)
            if (distance <= TAP_ADOPT_MAX_METERS && distance < bestDistance) {
                bestDistance = distance
                bestId = id
            }
        }
        bestId?.let { candidates[it]?.state = CandidateState.SCANNING }
        return bestId
    }

    /** Scan evidence 0..1 for candidate [id] (0 if unknown/never observed). See [progressOf] for the weighting. */
    fun progress(id: Long): Float = candidates[id]?.let(::progressOf) ?: 0f

    /** SCANNING candidates whose [progress] reached 1.0, transitioned to terminal COMPLETED and returned. Emit-once: a candidate returned here never appears again from any query method. */
    fun completedCandidates(): List<CompletedCandidate> {
        val result = mutableListOf<CompletedCandidate>()
        for (candidate in candidates.values) {
            if (candidate.state != CandidateState.SCANNING) continue
            if (progressOf(candidate) < 1.0f - PROGRESS_COMPLETE_EPSILON) continue
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
                ),
            )
        }
        return result
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

    /** (id, progress) for every currently-SCANNING candidate, for reticle rendering. */
    fun activeScanning(): List<Pair<Long, Float>> =
        candidates.values
            .filter { it.state == CandidateState.SCANNING }
            .map { it.packet.id to progressOf(it) }

    private fun progressOf(candidate: TrackedCandidate): Float {
        val angleTerm = min(candidate.angleBuckets.size / 3f, 1f)
        val closeTerm = min(candidate.closeFrames / 4f, 1f)
        val observationTerm = min(candidate.observations / 6f, 1f)
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
