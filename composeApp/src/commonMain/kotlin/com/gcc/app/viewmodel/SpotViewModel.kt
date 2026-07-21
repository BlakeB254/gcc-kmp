package com.gcc.app.viewmodel

import com.gcc.app.platform.ArMarkerQueue
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.DedupDecision
import com.gcc.app.platform.dedupUpgradeDecision
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.EnvScanSessionDraft
import com.gcc.app.platform.EnvScanSessionUploader
import com.gcc.app.platform.hasUserConfirmedLitter
import com.gcc.app.platform.ObjectCaptureOutcome
import com.gcc.app.platform.PROVENANCE_AUTO_DETECTED
import com.gcc.app.platform.PROVENANCE_USER_CONFIRMED
import com.gcc.app.platform.PROVENANCE_USER_REJECTED
import com.gcc.app.platform.removeLitterItemsNear
import com.gcc.app.platform.SessionPointsEstimator
import com.gcc.app.platform.TapCandidateState
import com.gcc.app.platform.TrashItemCatalog
import com.gcc.app.platform.UNKNOWN_LITTER_LABEL
import com.gcc.app.platform.ScanSessionTelemetry
import com.gcc.app.platform.capture.CaptureChunkUploader
import com.gcc.app.platform.deleteLocalFile
import com.gcc.app.platform.readLocalFileBytes
import com.gcc.app.platform.runOrphanRecoverySweepOnce
import com.gcc.app.platform.sessionEventLogPath
import com.gcc.app.platform.sessionJournalPath
import com.gcc.app.platform.getDeviceModel
import com.gcc.app.platform.getOsVersion
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.shared.serialization.GccJson
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.embedGpsExif
import com.gcc.app.ui.map.MapStyles
import com.gcc.shared.api.CaptureStorageNotConfiguredException
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.geo.gridCell10m
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sqrt

enum class SpotPhase { CAMERA, LOCATION, DESCRIPTION, SUBMITTING, RESULT }

enum class AreaMode { CIRCLE, RECTANGLE }

data class SpotUiState(
    val capturedPhotos: List<CapturedPhoto> = emptyList(),
    /** Complete Scan pressed with nothing to submit — screen should close. */
    val scanFinishedWithoutReport: Boolean = false,
    val currentLat: Double = MapStyles.NORTH_LAWNDALE_CENTER_LAT,
    val currentLng: Double = MapStyles.NORTH_LAWNDALE_CENTER_LNG,
    /** Spot pin position — null means use GPS position (currentLat/currentLng). */
    val spotLat: Double? = null,
    val spotLng: Double? = null,
    val affectedRadiusMeters: Double = 15.0,
    val hasAdjustedAffectedRadius: Boolean = false,
    val areaMode: AreaMode = AreaMode.CIRCLE,
    val rectWidth: Double = 20.0,
    val rectHeight: Double = 10.0,
    val rectRotation: Double = 0.0,
    val selectedTags: List<SpotTag> = emptyList(),
    val description: String = "",
    val isSubmitting: Boolean = false,
    val submittedReport: CommunityReport? = null,
    val matchingBounties: List<Bounty> = emptyList(),
    val createdBounty: Bounty? = null,
    val isCreatingBounty: Boolean = false,
    val bountyCreateError: String? = null,
    val error: String? = null,
    val phase: SpotPhase = SpotPhase.CAMERA,
    // On-device AI analysis
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val isAnalyzing: Boolean = false,
    val aiDescriptionApplied: Boolean = false,
    // Duplicate detection
    val duplicateMatch: DuplicateMatch? = null,
    val duplicateOverrideReason: String? = null,
    /** Trash items geo-tagged during the environment scan (minimap + submission). */
    val observedItems: List<ObservedSpotItem> = emptyList(),
    /** Guided 3D capture of large litter (measured USDZ model), when performed. */
    val objectCapture: ObjectCaptureResult? = null,
    /** The finished session's track + credited cells — drives the shaded
     *  coverage map on the Scan Session Complete screen. */
    val completedSession: EnvScanSessionDraft? = null,
    /** Live post-scan upload progress (photos + capture chunks + manifest)
     *  for the Scan Session Complete screen's upload card. */
    val uploadStatus: SessionUploadStatus? = null,
)

data class DuplicateMatch(
    val existingSpot: CommunityReport,
    val visualSimilarity: Float,
    val distanceMeters: Double,
)

class SpotViewModel(
    private val apiClient: GccApiClient,
    private val tokenManager: TokenManager,
    private val analyzer: OnDeviceAnalyzer,
    private val sessionUploader: EnvScanSessionUploader,
    private val captureChunkStore: CaptureChunkStore,
    private val captureUploader: CaptureChunkUploader,
) {
    companion object {
        const val MAX_PHOTOS = 3
        /** How long the session-summary upload card watches the concurrent
         *  capture-chunk drain before resolving to QUEUED. */
        const val CHUNK_WATCH_TIMEOUT_MS = 120_000L
        const val CHUNK_WATCH_POLL_MS = 750L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SpotUiState())
    val uiState: StateFlow<SpotUiState> = _uiState.asStateFlow()

    // Parallel to uiState.observedItems / uiState.capturedPhotos — client-side
    // only, never serialized to the wire model. Lets purgeRecentCaptures drop
    // whatever was captured inside an INDOOR gate's trailing privacy window.
    private val observedItemStamps = mutableListOf<Long>()
    private val photoStamps = mutableListOf<Long>()
    // Negative training examples (tapped + "Not litter") — never enter
    // uiState.observedItems / world_observations, only the R2 manifest.
    private val rejectedItems = mutableListOf<ObservedSpotItem>()
    // Task 17: indices (into capturedPhotos, kept in sync with photoStamps
    // the same way removePhoto/purgeRecentCaptures already do) of photos
    // captured with >= 2 identified-litter reticles on screen — the "best
    // shots" tag. Resolved to R2 keys at manifest-build time.
    private val multiItemPhotoIndices = mutableSetOf<Int>()

    init {
        // Catch up any session manifests that couldn't reach R2 last time
        // (offline, or storage not provisioned yet).
        scope.launch { sessionUploader.flushPending() }
        // Same catch-up for background capture chunks (Pillar 1) — opportunistic,
        // policy-gated (Wi-Fi/charging/cellular-opt-in); see CaptureChunkUploader.
        scope.launch { captureUploader.flushPending() }
        // Pillar 6 orphan recovery: an app kill mid-scan can leave a session
        // journal (GPS/gate-event track) and/or unbooked capture-chunk files
        // on disk with no manifest ever built for them. Guarded to at most
        // once per process inside runOrphanRecoverySweepOnce (RouteScanViewModel
        // calls it too, and either VM may construct first); active sessions
        // are excluded because journal files are created lazily on the first
        // recorded sample, never at recorder construction — see the sweep's
        // KDoc for the full reasoning and internal ordering.
        scope.launch { runOrphanRecoverySweepOnce(sessionUploader, captureChunkStore) }
    }

    // Confirmed litter flushes to /api/observations/ar-detected on the same
    // 30s cadence route scans use (SpotScreen's coverage loop drives it), so
    // tagged items reach the public map DURING the scan instead of only at
    // submit — and survive an abandoned session. The spot report submitted at
    // Complete Scan supersedes these early markers per cell+itemType
    // server-side, so no duplicate pins result.
    private val arMarkerQueue = ArMarkerQueue(
        submitFn = { markers ->
            try {
                apiClient.reportArDetectedMarkers(markers)
            } catch (e: Exception) {
                DiagnosticsLog.error("ar-marker-flush", "flush of ${markers.size} markers failed (kept queued)", e)
                throw e
            }
        },
    )

    fun flushArMarkers() {
        scope.launch { arMarkerQueue.flush() }
    }

    fun addPhoto(photo: CapturedPhoto, isMultiItemBestShot: Boolean = false) {
        val current = _uiState.value
        if (current.capturedPhotos.size >= MAX_PHOTOS) return
        val newIndex = current.capturedPhotos.size
        _uiState.value = current.copy(
            capturedPhotos = current.capturedPhotos + photo,
        )
        photoStamps.add(Clock.System.now().toEpochMilliseconds())
        if (isMultiItemBestShot) multiItemPhotoIndices.add(newIndex)

        // Trigger on-device AI analysis on the first photo
        if (current.capturedPhotos.isEmpty() && analyzer.isAvailable()) {
            scope.launch {
                _uiState.value = _uiState.value.copy(isAnalyzing = true)
                try {
                    val result = analyzer.analyzePhoto(photo.bytes, photo.depthAnalysis)
                    if (result != null) {
                        if (result.trashTypes.isNotEmpty()) {
                            addObservedItem(
                                ObservedSpotItem(
                                    lat = _uiState.value.currentLat,
                                    lng = _uiState.value.currentLng,
                                    // Analyzer emits display labels ("Glass",
                                    // "Tires") — canonicalize so glyphs and the
                                    // server taxonomy stay coherent.
                                    itemType = TrashItemCatalog.canonicalType(result.trashTypes.first()),
                                    severity = result.severity,
                                    confidence = result.confidence,
                                ),
                                // The analyzed photo IS the detection frame —
                                // ride it onto the marker so the map spot
                                // carries the image that prompted it.
                                photoBytes = photo.bytes.takeIf { it.isNotEmpty() },
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            aiAnalysis = result,
                            isAnalyzing = false,
                            // Pre-populate tags from AI if user hasn't selected any
                            selectedTags = if (_uiState.value.selectedTags.isEmpty())
                                result.suggestedTags else _uiState.value.selectedTags,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isAnalyzing = false)
                    }
                } catch (e: Exception) {
                    DiagnosticsLog.error("vision-analyze", "spot photo analysis failed", e)
                    _uiState.value = _uiState.value.copy(isAnalyzing = false)
                }
            }
        }
    }

    fun addScanPhoto(scanPhoto: ScanCapturedPhoto) {
        val photo = CapturedPhoto(
            bytes = scanPhoto.photoBytes ?: byteArrayOf(),
            width = scanPhoto.photoWidth,
            height = scanPhoto.photoHeight,
            depthAnalysis = scanPhoto.depthAnalysis,
            attestation = scanPhoto.attestation,
        )
        addPhoto(photo, isMultiItemBestShot = scanPhoto.isMultiItemBestShot)
        if (scanPhoto.aiAnalysis != null) {
            _uiState.value = _uiState.value.copy(aiAnalysis = scanPhoto.aiAnalysis)
        }
    }

    /**
     * Register a trash item observed during the environment scan. Deduped
     * against existing items of the same type within ~4m (the scanner path
     * projects along heading, the analyzer fallback tags the user position —
     * both can fire for the same pile).
     *
     * [photoBytes], when the caller has a frame available, is uploaded
     * best-effort before the marker is enqueued (never blocks it — a null or
     * failed upload just enqueues with no mediaUrl).
     */
    fun addObservedItem(item: ObservedSpotItem, photoBytes: ByteArray? = null) {
        val existing = _uiState.value.observedItems
        // C2: a same-type/4m match doesn't always mean "drop" — a fresh user
        // confirmation matching a prior auto-detected item is real evidence
        // (the only kind hasUserConfirmedLitter() accepts) and must upgrade
        // the existing item in place instead of being silently swallowed.
        // See dedupUpgradeDecision's kdoc for the full matrix.
        when (val decision = dedupUpgradeDecision(existing, item)) {
            is DedupDecision.Drop -> return
            is DedupDecision.Upgrade -> {
                val matched = existing[decision.index]
                val nowMs = Clock.System.now().toEpochMilliseconds()
                val upgraded = matched.copy(
                    provenance = PROVENANCE_USER_CONFIRMED,
                    // Preserve the original observation's atMs (manifest path
                    // matching keys off when the item was first seen, not
                    // when it was later confirmed) — only backfill if the
                    // prior item never got one.
                    atMs = matched.atMs ?: nowMs,
                )
                val updated = existing.toMutableList().also { it[decision.index] = upgraded }
                _uiState.value = _uiState.value.copy(observedItems = updated)
                DiagnosticsLog.info(
                    "env-scan",
                    "upgraded ${upgraded.itemType} @ ${upgraded.lat}, ${upgraded.lng} to user_confirmed " +
                        "(matched a prior auto-detected tick)",
                )
                enqueueLitterMarker(upgraded, photoBytes)
            }
            DedupDecision.Add -> {
                val nowMs = Clock.System.now().toEpochMilliseconds()
                // Stamp when the producer didn't: atMs is what lets post-processing
                // match this item to the manifest's GPS/heading path samples.
                val stamped = if (item.atMs == null) item.copy(atMs = nowMs) else item
                _uiState.value = _uiState.value.copy(observedItems = existing + stamped)
                observedItemStamps.add(nowMs)
                DiagnosticsLog.info("env-scan", "tagged ${item.itemType} @ ${item.lat}, ${item.lng} (${existing.size + 1} total)")
                enqueueLitterMarker(stamped, photoBytes)
            }
        }
    }

    /**
     * The user answered "Already clean" on a scan-detected item (TestFlight
     * 157 phantom-detection correction path): drop this session's litter
     * items within [radiusMeters] of the answered spot so the minimap zone,
     * glyphs and summary counts correct immediately. The server half (the
     * uploaded marker, if it landed) is rectified separately by the caller.
     */
    fun markObservedItemsCleanNear(lat: Double, lng: Double, radiusMeters: Double = 5.0) {
        val existing = _uiState.value.observedItems
        val remaining = removeLitterItemsNear(existing, lat, lng, radiusMeters)
        if (remaining.size == existing.size) return
        DiagnosticsLog.info(
            "env-scan",
            "marked ${existing.size - remaining.size} session item(s) clean near $lat, $lng",
        )
        _uiState.value = _uiState.value.copy(observedItems = remaining)
    }

    /**
     * Litter (not landmark elements) queues for the 30s marker flush; the
     * 10s indoor trailing purge usually beats the flush, same accepted
     * trade-off as route markers. Shared by both the fresh-add and the C2
     * upgrade path in [addObservedItem] — an upgrade needs the exact same
     * upload-then-enqueue-then-flush sequence a fresh add gets, just off the
     * upgraded (now user_confirmed) item instead of a brand-new one.
     */
    private fun enqueueLitterMarker(stamped: ObservedSpotItem, photoBytes: ByteArray?) {
        if (stamped.domain != null && stamped.domain != "litter") return
        scope.launch {
            try {
                // Upload-then-enqueue: raw bytes never enter the persisted
                // ArMarkerQueue — only the resolved URL (or null on failure).
                val mediaUrl = photoBytes?.let { bytes ->
                    try {
                        apiClient.uploadSpotPhotos(listOf(bytes)).firstOrNull()
                    } catch (e: Exception) {
                        DiagnosticsLog.error("env-scan", "marker photo upload failed (enqueuing without photo)", e)
                        null
                    }
                }
                arMarkerQueue.enqueue(
                    ArDetectedMarkerRequest(
                        lat = stamped.lat,
                        lng = stamped.lng,
                        label = TrashItemCatalog.canonicalType(stamped.itemType),
                        capturedAt = Clock.System.now(),
                        mediaUrl = mediaUrl,
                        provenance = stamped.provenance ?: PROVENANCE_AUTO_DETECTED,
                        estimatedWeightGrams = stamped.estimatedWeightGrams,
                    ),
                )
                // Real-time streaming (spec system 3): flush immediately on
                // confirm instead of waiting for the 30s loop — the public
                // map should reflect a newly tagged item right away. The
                // 30s loop in SpotScreen stays live as the retry sweep for
                // whatever this flush can't land (offline, in-flight, etc).
                arMarkerQueue.flush()
            } catch (e: Exception) {
                // Unguarded launch precedent (r2a): a SerializationException
                // or similar failure here must not kill the process — this
                // marker enqueue is best-effort, not required for the scan
                // to complete.
                DiagnosticsLog.error("env-scan", "arMarkerQueue.enqueue failed", e)
            }
        }
    }

    /** The user tapped a candidate and pressed "Confirm litter" (constraint 2).
     *  [photo] is the confirm-time evidence frame (best-effort): it rides the
     *  marker upload AND joins [capturedPhotos], so a taps-only session
     *  carries report-grade evidence — before this, tap confirmations filed
     *  photoless and weightless, and a session with only taps could never
     *  pass completeScan's photos+evidence report gate at all. */
    fun addConfirmedTapItem(candidate: TapCandidateState, photo: CapturedPhoto? = null) {
        val lat = candidate.lat ?: return
        val lng = candidate.lng ?: return
        ScanSessionTelemetry.active?.confirm(candidate.label ?: UNKNOWN_LITTER_LABEL)
        val itemType = TrashItemCatalog.canonicalType(candidate.label ?: UNKNOWN_LITTER_LABEL)
        photo?.let { addPhoto(it) }
        addObservedItem(
            ObservedSpotItem(
                lat = lat,
                lng = lng,
                itemType = itemType,
                confidence = candidate.confidence,
                domain = "litter",
                provenance = PROVENANCE_USER_CONFIRMED,
                // No LiDAR box on the tap path — use the type's typical mass
                // so the live weight tally and the marker's weight field stop
                // reading a confirmed item as 0 g.
                estimatedWeightGrams = TrashItemCatalog.estimateWeightGrams(itemType, null),
            ),
            photoBytes = photo?.bytes,
        )
    }

    /** A ground-candidate scan completed (auto or via the intent prompt). A
     *  user-intent completion is explicit confirmation: its evidence photo
     *  also joins [capturedPhotos] so the report gate and the filed report
     *  see it, exactly like a tap confirm's frame. */
    fun addScannedItem(item: ObservedSpotItem, photo: CapturedPhoto?) {
        if (item.provenance == PROVENANCE_USER_CONFIRMED) photo?.let { addPhoto(it) }
        addObservedItem(item, photo?.bytes)
    }

    /** The user tapped a candidate and pressed "Not litter" — a negative example.
     *  Kept OUT of world_observations; it rides the R2 manifest only. */
    fun addRejectedTapItem(candidate: TapCandidateState) {
        val lat = candidate.lat ?: return
        val lng = candidate.lng ?: return
        ScanSessionTelemetry.active?.reject(candidate.label ?: UNKNOWN_LITTER_LABEL)
        rejectedItems.add(
            ObservedSpotItem(
                lat = lat,
                lng = lng,
                itemType = TrashItemCatalog.canonicalType(candidate.label ?: UNKNOWN_LITTER_LABEL),
                confidence = candidate.confidence,
                domain = "litter",
                provenance = PROVENANCE_USER_REJECTED,
            ),
        )
        DiagnosticsLog.info("env-scan", "negative example recorded at $lat,$lng")
    }

    fun removePhoto(index: Int) {
        val current = _uiState.value.capturedPhotos.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _uiState.value = _uiState.value.copy(capturedPhotos = current)
            // Keep photoStamps aligned to capturedPhotos by index — otherwise
            // a later purgeRecentCaptures cutoff would be checked against the
            // wrong photo's timestamp.
            if (index in photoStamps.indices) photoStamps.removeAt(index)
            // Same re-indexing for multiItemPhotoIndices (Task 17): drop the
            // removed index, shift every later index down by one.
            val reindexed = multiItemPhotoIndices.mapNotNullTo(mutableSetOf()) { i ->
                when {
                    i == index -> null
                    i > index -> i - 1
                    else -> i
                }
            }
            multiItemPhotoIndices.clear()
            multiItemPhotoIndices.addAll(reindexed)
        }
    }

    fun setLocation(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(currentLat = lat, currentLng = lng)
    }

    fun setDescription(text: String) {
        _uiState.value = _uiState.value.copy(description = text)
    }

    fun setRadius(meters: Double) {
        _uiState.value = _uiState.value.copy(
            affectedRadiusMeters = meters,
            hasAdjustedAffectedRadius = true,
        )
    }

    fun setSpotLocation(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(spotLat = lat, spotLng = lng)
    }

    fun setAreaMode(mode: AreaMode) {
        _uiState.value = _uiState.value.copy(areaMode = mode)
    }

    fun setRectDimensions(width: Double, height: Double) {
        _uiState.value = _uiState.value.copy(rectWidth = width, rectHeight = height)
    }

    fun setRectRotation(deg: Double) {
        _uiState.value = _uiState.value.copy(rectRotation = deg)
    }

    fun applyAiDescription() {
        val ai = _uiState.value.aiAnalysis ?: return
        val desc = ai.generatedDescription ?: return
        if (_uiState.value.description.isBlank()) {
            _uiState.value = _uiState.value.copy(
                description = desc,
                aiDescriptionApplied = true,
            )
        }
    }

    fun setDuplicateOverrideReason(reason: String) {
        _uiState.value = _uiState.value.copy(duplicateOverrideReason = reason)
    }

    fun dismissDuplicate() {
        _uiState.value = _uiState.value.copy(duplicateMatch = null)
    }

    fun toggleTag(tag: SpotTag) {
        val current = _uiState.value.selectedTags.toMutableList()
        if (tag in current) current.remove(tag) else current.add(tag)
        _uiState.value = _uiState.value.copy(selectedTags = current)
    }

    /**
     * One-tap completion from the camera phase ("Complete Scan"): auto-apply
     * the on-device AI description (tags were pre-filled by [addPhoto]) and
     * submit directly with the GPS position (spotLat/spotLng stay null →
     * [submitReport] falls back to currentLat/currentLng). The manual
     * LOCATION/DESCRIPTION phases remain reachable via "Add details"; a
     * submit failure lands there with the error shown, so nothing is lost.
     */
    /** The session snapshot travelling with the next successful submission. */
    private var pendingSessionDraft: EnvScanSessionDraft? = null

    /** The captureHealth JSON (Task 5) travelling alongside [pendingSessionDraft]
     *  — set once at [completeScan] time, read by every [uploadEnvScanSession]
     *  call site that consumes the pending draft. Null off-iOS/no capture/
     *  failure. */
    private var pendingCaptureHealthJson: String? = null

    /** Session ids whose manifest already shipped — a submit retry after a
     *  409/failure archive must not double-archive the same session. */
    private val archivedSessionIds = mutableSetOf<String>()

    /**
     * Attach a guided 3D capture: derive the bulk-weight estimate from the
     * measured volume, then upload the USDZ to R2 in the background (the
     * submission ships whatever is attached — dimensions are valuable even
     * when the model upload fails or R2 is unprovisioned).
     */
    fun attachObjectCapture(outcome: ObjectCaptureOutcome) {
        // ObjectCaptureResult's dims are non-nullable @Serializable Doubles —
        // a NaN/infinite measurement (degenerate photogrammetry bounds) would
        // throw on JSON encode at submit time (kotlinx.serialization rejects
        // special floating-point values by default) rather than failing here
        // where it's recoverable. Treat non-finite dims as "no measurement":
        // skip attaching them (and the USDZ upload below) entirely.
        if (!outcome.widthMeters.isFinite() || !outcome.heightMeters.isFinite() ||
            !outcome.depthMeters.isFinite() || !outcome.volumeLiters.isFinite()
        ) {
            DiagnosticsLog.error(
                "object-capture",
                "non-finite capture dims (w=${outcome.widthMeters} h=${outcome.heightMeters} " +
                    "d=${outcome.depthMeters} vol=${outcome.volumeLiters}) — skipping dimension attach",
            )
            return
        }
        val result = ObjectCaptureResult(
            r2Key = null,
            widthMeters = outcome.widthMeters,
            heightMeters = outcome.heightMeters,
            depthMeters = outcome.depthMeters,
            volumeLiters = outcome.volumeLiters,
            estimatedWeightGrams = TrashItemCatalog.estimateWeightFromVolumeLiters(outcome.volumeLiters),
        )
        _uiState.value = _uiState.value.copy(objectCapture = result)
        DiagnosticsLog.info(
            "object-capture",
            "attached: ${outcome.widthMeters}x${outcome.heightMeters}x${outcome.depthMeters}m " +
                "≈${outcome.volumeLiters}L ≈${result.estimatedWeightGrams}g",
        )
        val path = outcome.usdzPath ?: return
        scope.launch {
            try {
                val bytes = readLocalFileBytes(path) ?: run {
                    DiagnosticsLog.error("object-capture", "USDZ unreadable at $path")
                    return@launch
                }
                val state = _uiState.value
                val cell = SessionPointsEstimator.gridCell10m(
                    state.spotLat ?: state.currentLat,
                    state.spotLng ?: state.currentLng,
                )
                val ticket = apiClient.createCaptureUploadUrl(
                    CaptureUploadUrlRequest(
                        locationCellId = "${cell.first}:${cell.second}",
                        deviceId = CoverageDeviceId.value,
                        capturedAt = "oc-${Clock.System.now().toEpochMilliseconds()}",
                        ext = "usdz",
                    )
                )
                apiClient.uploadToPresignedUrl(ticket.uploadUrl, bytes, "model/vnd.usdz+zip")
                _uiState.value = _uiState.value.copy(
                    objectCapture = _uiState.value.objectCapture?.copy(r2Key = ticket.r2Key),
                )
                DiagnosticsLog.info("object-capture", "USDZ archived: ${ticket.r2Key} (${bytes.size} bytes)")
            } catch (e: CaptureStorageNotConfiguredException) {
                DiagnosticsLog.info("object-capture", "R2 not provisioned — model kept local, dims still submit")
            } catch (e: Exception) {
                DiagnosticsLog.error("object-capture", "USDZ upload failed — dims still submit", e)
            }
        }
    }

    fun completeScan(sessionDraft: EnvScanSessionDraft? = null, captureHealthJson: String? = null) {
        val state = _uiState.value
        if (state.phase != SpotPhase.CAMERA) return
        // A scan is a COVERAGE act, not an incident report: every location is
        // supposed to be scanned, so finishing one only files a report when
        // the session produced litter evidence. Clean-area scans — photos or
        // not — archive the full session bundle and land on the session
        // summary.
        // Constraint 10: the whole-frame classifier (aiAnalysis.trashTypes) is a
        // weak routing signal and may NOT file a report. Only a candidate the
        // user explicitly confirmed is evidence.
        val litterEvidence = hasUserConfirmedLitter(state.observedItems)
        if (state.capturedPhotos.isEmpty() || !litterEvidence) {
            // Complete Scan ALWAYS ends the session (product rule: the
            // bottom button is the only exit). The session manifest —
            // GPS/heading track, scanned cells, photos, tagged items — is
            // still archived to R2 either way.
            DiagnosticsLog.info(
                "spot-submit",
                "Complete Scan: coverage-only session (${state.capturedPhotos.size} photo(s), " +
                    "litterEvidence=$litterEvidence) — archiving, no incident report",
            )
            sessionDraft?.let { uploadEnvScanSession(it, reportId = null, captureHealthJson = captureHealthJson) }
            if (sessionDraft != null && sessionDraft.scannedCells.isNotEmpty()) {
                // Ground WAS covered — show the session-complete summary
                // with the shaded coverage map instead of silently dropping
                // the user on the dashboard.
                _uiState.value = _uiState.value.copy(
                    phase = SpotPhase.RESULT,
                    completedSession = sessionDraft,
                )
            } else {
                // I4: zero-cells coverage-only close. Nothing was scanned, no
                // session summary is shown (phase stays CAMERA — RESULT is
                // never entered on this path), and the LaunchedEffect keyed
                // on scanFinishedWithoutReport in SpotScreen navigates away
                // (onBack) on the very next recomposition. But the VM is a
                // Koin singleton, so anything session-scoped left behind here
                // bleeds into the NEXT scan opened in this same VM instance.
                // uploadEnvScanSession (above) already snapshotted
                // rejectedItems/multiItemPhotoIndices synchronously before
                // this point, so clearing them now cannot race its launched
                // upload coroutine. observedItems/capturedPhotos are cleared
                // too — CAMERA-phase UI briefly recomposes with them empty
                // (harmless: 0 photos/0 items) before onBack tears it down,
                // and RESULT (which reads uiState.observedItems /
                // .capturedPhotos for the summary) is never reached here.
                rejectedItems.clear()
                multiItemPhotoIndices.clear()
                observedItemStamps.clear()
                photoStamps.clear()
                _uiState.value = _uiState.value.copy(
                    scanFinishedWithoutReport = true,
                    observedItems = emptyList(),
                    capturedPhotos = emptyList(),
                )
            }
            return
        }
        pendingSessionDraft = sessionDraft
        pendingCaptureHealthJson = captureHealthJson
        DiagnosticsLog.info(
            "spot-submit",
            "one-tap Complete Scan with ${state.capturedPhotos.size} photo(s) + litter evidence — filing report",
        )
        applyAiDescription()
        submitReport()
    }

    /**
     * Archive the whole scan session to R2 as ONE capture: every photo plus a
     * manifest carrying the GPS/facing track, timing, scanned-area cells, and
     * tagged items. Fire-and-forget after the report submits — a 501 means R2
     * env vars aren't provisioned yet, which is expected and only logged.
     */
    private fun uploadEnvScanSession(draft: EnvScanSessionDraft, reportId: Int?, captureHealthJson: String? = null) {
        if (!archivedSessionIds.add(draft.sessionId)) return
        val state = _uiState.value
        // I4: rejectedItems/multiItemPhotoIndices are mutable session-scoped
        // fields (not part of the immutable uiState snapshot above), so a
        // caller that clears them right after this call returns — see the
        // zero-cells coverage-only close in completeScan — would otherwise
        // race the launched coroutine below, which doesn't read them until
        // well after upload I/O suspends. Snapshot both HERE, synchronously,
        // before the coroutine is even scheduled, so the clear can never
        // observe (or cause) a torn read.
        val rejectedSnapshot = rejectedItems.toList()
        val multiItemPhotoIndicesSnapshot = multiItemPhotoIndices.toSet()
        // Flight recorder: capture the session's event log SYNCHRONOUSLY —
        // the screen's dispose (navigation away) deactivates the registry,
        // and this coroutine may not run until after that. The reference
        // stays snapshottable regardless of registry state.
        val sessionEventLog = ScanSessionTelemetry.forSession(draft.sessionId)
        scope.launch {
            try {
                val anchorLat = state.spotLat ?: state.currentLat
                val anchorLng = state.spotLng ?: state.currentLng
                val cell = SessionPointsEstimator.gridCell10m(anchorLat, anchorLng)
                val cellId = "${cell.first}:${cell.second}"
                val deviceId = CoverageDeviceId.value
                val stamp = "${draft.sessionId}"

                // Upload-progress card (Scan Session Complete screen): one
                // step per photo, per capture-chunk file, plus one for the
                // manifest+event-log bundle. Chunk uploads run concurrently
                // in CaptureChunkUploader (kicked at scan completion and by
                // the activation flush) — their progress is polled from the
                // store, everything else is updated inline below.
                val initialChunks = try {
                    captureChunkStore.chunksForSession(draft.sessionId)
                } catch (e: Exception) {
                    emptyList()
                }
                val chunkTotal = initialChunks.size
                var chunksDone = initialChunks.count { it.uploadedAtMs != null }
                val photoTotal = state.capturedPhotos.size
                val totalSteps = photoTotal + chunkTotal + 1
                var photosDone = 0
                var manifestDelivered = false
                fun publishProgress(uploadState: SessionUploadStatus.State) {
                    _uiState.value = _uiState.value.copy(
                        uploadStatus = SessionUploadStatus(
                            sessionId = draft.sessionId,
                            doneSteps = photosDone + chunksDone + (if (manifestDelivered) 1 else 0),
                            totalSteps = totalSteps,
                            state = uploadState,
                        ),
                    )
                }
                publishProgress(SessionUploadStatus.State.UPLOADING)

                // Photos upload inline (their bytes only exist in memory now);
                // a 501/offline here just means the manifest ships photoless —
                // the photos are already durable on the community report.
                val photoKeys = mutableListOf<String>()
                try {
                    state.capturedPhotos.forEachIndexed { index, photo ->
                        val ticket = apiClient.createCaptureUploadUrl(
                            CaptureUploadUrlRequest(
                                locationCellId = cellId,
                                deviceId = deviceId,
                                capturedAt = "$stamp-photo$index",
                                ext = "jpg",
                            )
                        )
                        apiClient.uploadToPresignedUrl(ticket.uploadUrl, photo.bytes, "image/jpeg")
                        photoKeys.add(ticket.r2Key)
                        photosDone++
                        publishProgress(SessionUploadStatus.State.UPLOADING)
                    }
                } catch (e: CaptureStorageNotConfiguredException) {
                    DiagnosticsLog.info("env-capture", "R2 not provisioned — photos skipped, manifest will queue")
                } catch (e: Exception) {
                    DiagnosticsLog.error("env-capture", "photo upload to R2 failed — manifest continues without", e)
                }

                // Task 17 best-shot tagging: multiItemPhotoIndices (indices
                // into state.capturedPhotos) resolved against photoKeys, which
                // the forEachIndexed loop above populated in the same order —
                // an index only resolves if its upload actually succeeded
                // (photoKeys is shorter than capturedPhotos when the loop
                // above threw partway through).
                val multiItemPhotoKeys = multiItemPhotoIndicesSnapshot
                    .filter { it < photoKeys.size }
                    .sorted()
                    .map { photoKeys[it] }
                    .ifEmpty { null }

                // Uploaded-so-far capture-chunk R2 keys (Pillar 1): late
                // uploads (still-pending chunks, or ones the policy gate
                // hasn't cleared yet) are keyed by sessionId in R2 regardless
                // of whether they made it into this manifest snapshot.
                val captureChunkKeys = try {
                    captureChunkStore.chunksForSession(draft.sessionId).mapNotNull { it.r2Key }
                } catch (e: Exception) {
                    DiagnosticsLog.error("env-capture", "captureChunks lookup failed (manifest continues without)", e)
                    emptyList()
                }

                val completedAtMs = Clock.System.now().toEpochMilliseconds()
                // Splat-capture reliability spec, Task 5: the native health
                // JSON carries everything except sessionSeconds, which only
                // Kotlin can compute (it needs both manifest timestamps).
                // Decode failures (malformed JSON, off-iOS null) drop the
                // whole block rather than ship a garbage partial summary.
                val captureHealth = captureHealthJson?.let { json ->
                    runCatching { GccJson.decodeFromString(CaptureHealth.serializer(), json) }
                        .onFailure { e ->
                            DiagnosticsLog.error("env-capture", "captureHealth decode failed (manifest continues without)", e)
                        }
                        .getOrNull()
                        ?.copy(sessionSeconds = (completedAtMs - draft.startedAtMs) / 1000.0)
                }

                val manifest = EnvScanManifest(
                    sessionId = draft.sessionId,
                    deviceId = deviceId,
                    deviceModel = com.gcc.app.platform.getDeviceModel(),
                    osVersion = com.gcc.app.platform.getOsVersion(),
                    startedAtMs = draft.startedAtMs,
                    completedAtMs = completedAtMs,
                    path = draft.path,
                    scannedCells = draft.scannedCells,
                    scannedCellSizeMeters = 3.0,
                    observedItems = state.observedItems,
                    photoKeys = photoKeys,
                    communityReportId = reportId,
                    objectCapture = state.objectCapture,
                    gateEvents = draft.gateEvents,
                    captureChunks = captureChunkKeys.ifEmpty { null },
                    captureSchema = if (captureChunkKeys.isNotEmpty()) 3 else null,
                    captureHealth = captureHealth,
                    // World-splat registration (DGX): AR↔world pairings +
                    // the AR axes convention the pose sidecar uses.
                    geoRefEvents = draft.geoRefEvents.ifEmpty { null },
                    arFrameConvention = EnvScanManifest.AR_FRAME_CONVENTION,
                    rejectedItems = rejectedSnapshot.ifEmpty { null },
                    diagnosticsLog = DiagnosticsLog.tailForManifest(),
                    multiItemPhotoKeys = multiItemPhotoKeys,
                )
                // Flight recorder (scan-session-replay PRD Phase 1): the
                // session's event stream ALWAYS ships on completion — the
                // uploader gzips it, uploads it as its own R2 artifact
                // BEFORE the manifest (which references it via eventLogKey),
                // and queues the pair together on failure. Snapshot before
                // deactivating so the stream is complete.
                val eventLines = sessionEventLog?.snapshotLines()
                ScanSessionTelemetry.deactivateSession(draft.sessionId)
                manifestDelivered = sessionUploader.uploadOrQueue(manifest, eventLines)
                if (manifestDelivered) {
                    DiagnosticsLog.info(
                        "env-capture",
                        "session ${draft.sessionId} archived to R2: ${photoKeys.size} photo(s) + manifest " +
                            "(${draft.path.size} track samples, ${draft.scannedCells.size} cells)",
                    )
                }
                publishProgress(SessionUploadStatus.State.UPLOADING)
                // Pillar 6: the manifest is now durable either in R2 or in
                // EnvScanSessionUploader's own persistent retry queue — the
                // on-disk journal's only job (surviving an app kill before
                // this point) is done, so delete it. Same for the event-log
                // mirror: its lines are durable in R2 or the uploader's
                // pending-event-log store. A missing journal (desktop/android
                // — journalPath is always null there) is a no-op.
                sessionJournalPath(draft.sessionId)?.let { deleteLocalFile(it) }
                sessionEventLogPath(draft.sessionId)?.let { deleteLocalFile(it) }

                // Watch the concurrent chunk drain so the summary card can
                // resolve instead of hanging at "uploading" forever. Poisoned
                // rows (uploadedAtMs = -1, permanent 4xx skip) count as
                // terminal. On timeout with rows still pending the card reads
                // QUEUED — accurate: they persist in CaptureChunkStore and
                // retry on the next flush trigger (activation, next scan).
                val watchDeadline = Clock.System.now().toEpochMilliseconds() + CHUNK_WATCH_TIMEOUT_MS
                while (chunksDone < chunkTotal && Clock.System.now().toEpochMilliseconds() < watchDeadline) {
                    delay(CHUNK_WATCH_POLL_MS)
                    val records = try {
                        captureChunkStore.chunksForSession(draft.sessionId)
                    } catch (e: Exception) {
                        break
                    }
                    chunksDone = records.count { it.uploadedAtMs != null }
                    publishProgress(SessionUploadStatus.State.UPLOADING)
                }
                publishProgress(
                    if (manifestDelivered && chunksDone >= chunkTotal) SessionUploadStatus.State.DONE
                    else SessionUploadStatus.State.QUEUED
                )
            } catch (e: Exception) {
                DiagnosticsLog.error("env-capture", "session archive to R2 failed (report unaffected)", e)
                // Resolve the progress card to QUEUED rather than leaving it
                // stuck mid-bar — uploadOrQueue/the chunk store persist
                // everything that failed, so "will retry" is the truth.
                _uiState.value.uploadStatus?.takeIf { it.sessionId == draft.sessionId }?.let { s ->
                    _uiState.value = _uiState.value.copy(
                        uploadStatus = s.copy(state = SessionUploadStatus.State.QUEUED),
                    )
                }
            }
        }
    }

    fun nextPhase() {
        val current = _uiState.value.phase
        val next = when (current) {
            SpotPhase.CAMERA -> SpotPhase.LOCATION
            SpotPhase.LOCATION -> {
                // Check for nearby duplicates before advancing to DESCRIPTION
                checkForDuplicates()
                SpotPhase.DESCRIPTION
            }
            SpotPhase.DESCRIPTION -> {
                submitReport()
                return
            }
            SpotPhase.SUBMITTING -> return
            SpotPhase.RESULT -> return
        }
        _uiState.value = _uiState.value.copy(phase = next, error = null)
        // Auto-apply AI description when entering DESCRIPTION phase
        if (next == SpotPhase.DESCRIPTION) applyAiDescription()
    }

    private fun checkForDuplicates() {
        scope.launch {
            try {
                val state = _uiState.value
                val lat = state.spotLat ?: state.currentLat
                val lng = state.spotLng ?: state.currentLng

                // Match the server's duplicate radius (~15m). The earlier
                // 50m radius routinely crossed streets and alleys and
                // blocked legitimate separate spots — see server-side
                // /api/spots route for the same constant.
                val nearbySpots = apiClient.getSpots(lat = lat, lng = lng, radiusMeters = 15.0)
                if (nearbySpots.isEmpty()) return@launch

                var bestMatch: DuplicateMatch? = null

                for (spot in nearbySpots) {
                    val distance = GeofenceVerification.distanceMeters(lat, lng, spot.coordinates.lat, spot.coordinates.lng)
                    // Only flag at <8m as visually likely-duplicate (one
                    // dump pile rather than two). Between 8m and the
                    // server's 15m hard limit the server will return a
                    // 409 if it really is a true overlap, but the client
                    // shouldn't volunteer a confidence-flag confirmation.
                    val similarity = when {
                        distance < 4.0 -> 0.9f
                        distance < 8.0 -> 0.75f
                        else -> 0f
                    }

                    if (similarity >= 0.75f) {
                        if (bestMatch == null || similarity > bestMatch.visualSimilarity) {
                            bestMatch = DuplicateMatch(
                                existingSpot = spot,
                                visualSimilarity = similarity,
                                distanceMeters = distance,
                            )
                        }
                    }
                }

                if (bestMatch != null) {
                    _uiState.value = _uiState.value.copy(duplicateMatch = bestMatch)
                }
            } catch (e: Exception) {
                // Don't block the flow if duplicate check fails
                DiagnosticsLog.error("spot-duplicates", "duplicate check failed (non-blocking)", e)
            }
        }
    }

    fun submitReport() {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                phase = SpotPhase.SUBMITTING,
                isSubmitting = true,
                error = null,
            )
            try {
                val state = _uiState.value
                val submitLat = state.spotLat ?: state.currentLat
                val submitLng = state.spotLng ?: state.currentLng

                // Embed GPS EXIF into photos and upload via typed API (includes auth header)
                val uploadedUrls = mutableListOf<String>()
                if (state.capturedPhotos.isNotEmpty()) {
                    val photoBytesWithGps = state.capturedPhotos.map { photo ->
                        embedGpsExif(photo.bytes, submitLat, submitLng)
                    }
                    val urls = apiClient.uploadSpotPhotos(photoBytesWithGps)
                    uploadedUrls.addAll(urls)
                }

                // Collect depth analysis from the first photo that has it
                val depthAnalysis = state.capturedPhotos.firstNotNullOfOrNull { it.depthAnalysis }

                val effectiveRadiusMeters = state.effectiveAffectedRadiusMeters()

                // Build affected area from mode
                val affectedArea: AffectedArea? = when (state.areaMode) {
                    AreaMode.CIRCLE -> AffectedArea.Circle(radiusMeters = effectiveRadiusMeters)
                    AreaMode.RECTANGLE -> AffectedArea.Rectangle(
                        widthMeters = state.rectWidth,
                        heightMeters = state.rectHeight,
                        rotationDeg = state.rectRotation,
                    )
                }

                // Collect capture attestation from the first photo
                val captureAttestation = state.capturedPhotos.firstNotNullOfOrNull { it.attestation }

                // Include on-device AI analysis
                val onDeviceAiResult = state.aiAnalysis

                // Pillar 8 task 1: scan submissions can additionally carry every
                // GRAZED+ cell the scan swept, so the server can supersede stale
                // reports across the whole sweep (not just the anchor +
                // observedItems radius). pendingSessionDraft.scannedCells IS this
                // ViewModel's coverage-tracker output — set from the AR env-scan
                // session's own CoverageTracker in completeScan() — so this reuses
                // it rather than standing up a second tracker. Only set on the
                // Complete Scan path (pendingSessionDraft is null for the manual
                // "Add details" flow, which never ran a scan session); the server
                // treats an absent field identically to an old client.
                val coveredCellIds = pendingSessionDraft?.scannedCells
                    ?.map { gridCell10m(it.lat, it.lng) }
                    ?.distinct()
                    ?.take(2000)
                    ?.takeIf { it.isNotEmpty() }

                val request = CreateSpotRequest(
                    type = CommunityReportType.SIGHTING,
                    lat = submitLat,
                    lng = submitLng,
                    description = state.description.ifBlank { null },
                    mediaUrls = uploadedUrls.ifEmpty { null },
                    affectedRadiusMeters = effectiveRadiusMeters,
                    affectedArea = affectedArea,
                    tags = state.selectedTags,
                    reporterName = if (tokenManager.isAdmin()) "GCC" else null,
                    depthAnalysis = depthAnalysis,
                    captureAttestation = captureAttestation,
                    onDeviceAiResult = onDeviceAiResult,
                    duplicateOverrideReason = state.duplicateOverrideReason,
                    // ALWAYS an array (even empty): the presence of the
                    // array is the server's scan marker — scans supersede
                    // nearby stale reports instead of 409ing. `null` here
                    // made zero-item scans read as manual reports and get
                    // rejected with 'already exists within 15m'.
                    observedItems = state.observedItems,
                    objectCapture = state.objectCapture,
                    coveredCellIds = coveredCellIds,
                )
                val response = apiClient.httpClient.post("${apiClient.baseUrl}/api/spots") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (response.status.value == 409) {
                    DiagnosticsLog.error("spot-submit", "server 409: duplicate spot within 15m")
                    // The REPORT is a duplicate but the SCAN still happened:
                    // archive the session bundle (GPS/heading/timestamp track,
                    // cells, items) so no completed scan ever vanishes.
                    pendingSessionDraft?.let { draft ->
                        pendingSessionDraft = null
                        uploadEnvScanSession(draft, reportId = null, captureHealthJson = pendingCaptureHealthJson)
                    }
                    // World-scan model: failures land back on the camera, not
                    // the legacy manual-details forms.
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        phase = SpotPhase.CAMERA,
                        error = "A report already exists within 15m — area already logged",
                    )
                    return@launch
                }
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    throw Exception("Server error ${response.status.value}: $errorBody")
                }
                val spotResponse = response.body<ApiResponse<SpotResponse>>()
                // Server credits the report row separately from the points
                // ledger; copy pointsAwarded onto the report so the success
                // card can render the "+N pts" reward without an extra fetch.
                val rewardedReport = spotResponse.data.report.copy(
                    observerRewardPoints = spotResponse.data.pointsAwarded
                        .takeIf { it > 0 }
                        ?: spotResponse.data.report.observerRewardPoints,
                )
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = SpotPhase.RESULT,
                    submittedReport = rewardedReport,
                    matchingBounties = spotResponse.data.matchingBounties,
                    // Session coverage feeds the completion screen's shaded map.
                    completedSession = pendingSessionDraft,
                )

                // One session = one capture: archive the full bundle to R2.
                pendingSessionDraft?.let { draft ->
                    pendingSessionDraft = null
                    uploadEnvScanSession(draft, rewardedReport.id, captureHealthJson = pendingCaptureHealthJson)
                }
            } catch (e: Exception) {
                DiagnosticsLog.error("spot-submit", "report submission failed", e)
                // Archive the scan bundle even though the report failed — the
                // uploader's persistent queue makes it durable offline, so the
                // session (geo/heading/timestamps) reaches the backend either
                // way. A retry that later succeeds skips the re-archive via
                // archivedSessionIds.
                pendingSessionDraft?.let { draft ->
                    uploadEnvScanSession(draft, reportId = null, captureHealthJson = pendingCaptureHealthJson)
                }
                // Back to the camera with the error surfaced as a popup —
                // Complete Scan can simply be tapped again.
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = SpotPhase.CAMERA,
                    error = e.message ?: "Submission failed — tap Complete Scan to retry",
                )
            }
        }
    }

    fun acceptBounty(bountyId: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            try {
                apiClient.acceptBounty(bountyId)
                // Remove accepted bounty from list
                _uiState.value = _uiState.value.copy(
                    matchingBounties = _uiState.value.matchingBounties.filter { it.id != bountyId },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to accept bounty",
                )
            }
        }
    }

    fun createBountyForSubmittedSpot(amountDollars: Int) {
        scope.launch {
            val report = _uiState.value.submittedReport ?: return@launch
            val amountCents = amountDollars * 100
            if (amountCents < 1000) {
                _uiState.value = _uiState.value.copy(bountyCreateError = "Minimum bounty amount is $10")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isCreatingBounty = true,
                bountyCreateError = null,
            )

            try {
                val bounty = apiClient.createBounty(
                    CreateBountyRequest(
                        title = "Cleanup bounty for report #${report.id}",
                        description = report.description,
                        amount = amountCents,
                        locationLat = report.coordinates.lat,
                        locationLng = report.coordinates.lng,
                        radiusMeters = report.affectedRadiusMeters,
                        mediaUrls = report.mediaUrls,
                        triggerReportId = report.id,
                        visibility = BountyVisibility.PUBLIC,
                        isAnonymous = true,
                    ),
                )

                _uiState.value = _uiState.value.copy(
                    createdBounty = bounty,
                    isCreatingBounty = false,
                    bountyCreateError = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreatingBounty = false,
                    bountyCreateError = e.message ?: "Failed to place bounty",
                )
            }
        }
    }

    /** Privacy purge on an INDOOR flip: drop items/photos captured in the
     *  gate's trailing window (walked inside while still flagged OUTDOOR). */
    fun purgeRecentCaptures(cutoffMs: Long) {
        val state = _uiState.value
        val keptItems = state.observedItems.filterIndexed { i, _ ->
            observedItemStamps.getOrNull(i)?.let { it < cutoffMs } ?: true
        }
        val keptPhotos = state.capturedPhotos.filterIndexed { i, _ ->
            photoStamps.getOrNull(i)?.let { it < cutoffMs } ?: true
        }
        val dropped = (state.observedItems.size - keptItems.size) + (state.capturedPhotos.size - keptPhotos.size)
        if (dropped > 0) {
            DiagnosticsLog.info("outdoor-gate", "privacy purge: dropped $dropped capture(s) from trailing window")
        }
        while (observedItemStamps.size > keptItems.size) observedItemStamps.removeLast()
        while (photoStamps.size > keptPhotos.size) photoStamps.removeLast()
        // Trailing-window purge only ever drops the most recent photos, so
        // the surviving indices are always the front [0, keptPhotos.size) —
        // same simplification the photoStamps tail-drop above relies on.
        multiItemPhotoIndices.removeAll { it >= keptPhotos.size }
        _uiState.value = state.copy(observedItems = keptItems, capturedPhotos = keptPhotos)
    }

    /**
     * Full session reset. The VM is a Koin singleton, so anything left over
     * from a prior scan session bleeds into the next one unless it's cleared
     * here — [pendingSessionDraft] and [archivedSessionIds] used to survive a
     * failed/abandoned session (the submitReport failure path never nulled
     * pendingSessionDraft, unlike the success and 409 paths), which could
     * carry stale draft/session-id state into the very next scan. Cross-session
     * state (arMarkerQueue, the coroutine scope) is intentionally left alone.
     */
    fun reset() {
        _uiState.value = SpotUiState()
        observedItemStamps.clear()
        photoStamps.clear()
        rejectedItems.clear()
        multiItemPhotoIndices.clear()
        pendingSessionDraft = null
        pendingCaptureHealthJson = null
        archivedSessionIds.clear()
    }

    /**
     * Consume the one-shot "scan finished without report" close signal. The
     * view model is a Koin singleton, so without this the flag stayed true
     * after the screen closed once — and every later Environment Scan open
     * saw it and instantly closed itself until the app restarted.
     */
    fun consumeScanFinishedSignal() {
        _uiState.value = _uiState.value.copy(scanFinishedWithoutReport = false)
    }

    private fun SpotUiState.effectiveAffectedRadiusMeters(): Double {
        if (hasAdjustedAffectedRadius) return affectedRadiusMeters

        val estimatedAreaSqFt = capturedPhotos
            .mapNotNull { it.depthAnalysis?.estimatedAreaSqFt }
            .filter { it > 0.0 }
            .maxOrNull()
            ?: return affectedRadiusMeters

        val estimatedAreaSqMeters = estimatedAreaSqFt * SQ_FT_TO_SQ_METERS
        return sqrt(estimatedAreaSqMeters / PI) + SCAN_RADIUS_BUFFER_METERS
    }
}

private const val SQ_FT_TO_SQ_METERS = 0.09290304
private const val SCAN_RADIUS_BUFFER_METERS = 1.0
