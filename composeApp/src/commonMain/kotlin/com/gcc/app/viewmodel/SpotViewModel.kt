package com.gcc.app.viewmodel

import com.gcc.app.platform.ArMarkerQueue
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.EnvScanSessionDraft
import com.gcc.app.platform.EnvScanSessionUploader
import com.gcc.app.platform.ObjectCaptureOutcome
import com.gcc.app.platform.SessionPointsEstimator
import com.gcc.app.platform.TrashItemCatalog
import com.gcc.app.platform.readLocalFileBytes
import com.gcc.app.platform.getDeviceModel
import com.gcc.app.platform.getOsVersion
import com.gcc.shared.serialization.GccJson
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.embedGpsExif
import com.gcc.app.ui.map.MapStyles
import com.gcc.shared.api.CaptureStorageNotConfiguredException
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.geo.GeofenceVerification
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
) {
    companion object {
        const val MAX_PHOTOS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SpotUiState())
    val uiState: StateFlow<SpotUiState> = _uiState.asStateFlow()

    // Parallel to uiState.observedItems / uiState.capturedPhotos — client-side
    // only, never serialized to the wire model. Lets purgeRecentCaptures drop
    // whatever was captured inside an INDOOR gate's trailing privacy window.
    private val observedItemStamps = mutableListOf<Long>()
    private val photoStamps = mutableListOf<Long>()

    init {
        // Catch up any session manifests that couldn't reach R2 last time
        // (offline, or storage not provisioned yet).
        scope.launch { sessionUploader.flushPending() }
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

    fun addPhoto(photo: CapturedPhoto) {
        val current = _uiState.value
        if (current.capturedPhotos.size >= MAX_PHOTOS) return
        _uiState.value = current.copy(
            capturedPhotos = current.capturedPhotos + photo,
        )
        photoStamps.add(Clock.System.now().toEpochMilliseconds())

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
                                )
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
        addPhoto(photo)
        if (scanPhoto.aiAnalysis != null) {
            _uiState.value = _uiState.value.copy(aiAnalysis = scanPhoto.aiAnalysis)
        }
    }

    /**
     * Register a trash item observed during the environment scan. Deduped
     * against existing items of the same type within ~4m (the scanner path
     * projects along heading, the analyzer fallback tags the user position —
     * both can fire for the same pile).
     */
    fun addObservedItem(item: ObservedSpotItem) {
        val existing = _uiState.value.observedItems
        val duplicate = existing.any { prior ->
            prior.itemType == item.itemType &&
                GeofenceVerification.distanceMeters(prior.lat, prior.lng, item.lat, item.lng) < 4.0
        }
        if (duplicate) return
        val nowMs = Clock.System.now().toEpochMilliseconds()
        // Stamp when the producer didn't: atMs is what lets post-processing
        // match this item to the manifest's GPS/heading path samples.
        val stamped = if (item.atMs == null) item.copy(atMs = nowMs) else item
        _uiState.value = _uiState.value.copy(observedItems = existing + stamped)
        observedItemStamps.add(nowMs)
        DiagnosticsLog.info("env-scan", "tagged ${item.itemType} @ ${item.lat}, ${item.lng} (${existing.size + 1} total)")
        // Litter (not landmark elements) also queues for the 30s marker
        // flush; the 10s indoor trailing purge usually beats the flush, same
        // accepted trade-off as route markers.
        if (stamped.domain == null || stamped.domain == "litter") {
            scope.launch {
                arMarkerQueue.enqueue(
                    ArDetectedMarkerRequest(
                        lat = stamped.lat,
                        lng = stamped.lng,
                        label = TrashItemCatalog.canonicalType(stamped.itemType),
                        capturedAt = Clock.System.now(),
                    ),
                )
            }
        }
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

    fun completeScan(sessionDraft: EnvScanSessionDraft? = null) {
        val state = _uiState.value
        if (state.phase != SpotPhase.CAMERA) return
        if (state.capturedPhotos.isEmpty()) {
            // Complete Scan ALWAYS ends the session (product rule: the
            // bottom button is the only exit). With no captures there is
            // no report to file, but the session manifest — GPS/heading
            // track, scanned cells, tagged items — is still archived, and
            // the view closes. (Silently doing nothing here stranded the
            // user on the camera.)
            DiagnosticsLog.info("spot-submit", "Complete Scan with no captures — archiving session, closing")
            sessionDraft?.let { uploadEnvScanSession(it, reportId = null) }
            _uiState.value = _uiState.value.copy(scanFinishedWithoutReport = true)
            return
        }
        pendingSessionDraft = sessionDraft
        DiagnosticsLog.info("spot-submit", "one-tap Complete Scan with ${state.capturedPhotos.size} photo(s)")
        applyAiDescription()
        submitReport()
    }

    /**
     * Archive the whole scan session to R2 as ONE capture: every photo plus a
     * manifest carrying the GPS/facing track, timing, scanned-area cells, and
     * tagged items. Fire-and-forget after the report submits — a 501 means R2
     * env vars aren't provisioned yet, which is expected and only logged.
     */
    private fun uploadEnvScanSession(draft: EnvScanSessionDraft, reportId: Int?) {
        if (!archivedSessionIds.add(draft.sessionId)) return
        val state = _uiState.value
        scope.launch {
            try {
                val anchorLat = state.spotLat ?: state.currentLat
                val anchorLng = state.spotLng ?: state.currentLng
                val cell = SessionPointsEstimator.gridCell10m(anchorLat, anchorLng)
                val cellId = "${cell.first}:${cell.second}"
                val deviceId = CoverageDeviceId.value
                val stamp = "${draft.sessionId}"

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
                    }
                } catch (e: CaptureStorageNotConfiguredException) {
                    DiagnosticsLog.info("env-capture", "R2 not provisioned — photos skipped, manifest will queue")
                } catch (e: Exception) {
                    DiagnosticsLog.error("env-capture", "photo upload to R2 failed — manifest continues without", e)
                }

                val manifest = EnvScanManifest(
                    sessionId = draft.sessionId,
                    deviceId = deviceId,
                    deviceModel = com.gcc.app.platform.getDeviceModel(),
                    osVersion = com.gcc.app.platform.getOsVersion(),
                    startedAtMs = draft.startedAtMs,
                    completedAtMs = Clock.System.now().toEpochMilliseconds(),
                    path = draft.path,
                    scannedCells = draft.scannedCells,
                    scannedCellSizeMeters = 3.0,
                    observedItems = state.observedItems,
                    photoKeys = photoKeys,
                    communityReportId = reportId,
                    objectCapture = state.objectCapture,
                    gateEvents = draft.gateEvents,
                )
                if (sessionUploader.uploadOrQueue(manifest)) {
                    DiagnosticsLog.info(
                        "env-capture",
                        "session ${draft.sessionId} archived to R2: ${photoKeys.size} photo(s) + manifest " +
                            "(${draft.path.size} track samples, ${draft.scannedCells.size} cells)",
                    )
                }
            } catch (e: Exception) {
                DiagnosticsLog.error("env-capture", "session archive to R2 failed (report unaffected)", e)
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
                        uploadEnvScanSession(draft, reportId = null)
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
                )

                // One session = one capture: archive the full bundle to R2.
                pendingSessionDraft?.let { draft ->
                    pendingSessionDraft = null
                    uploadEnvScanSession(draft, rewardedReport.id)
                }
            } catch (e: Exception) {
                DiagnosticsLog.error("spot-submit", "report submission failed", e)
                // Archive the scan bundle even though the report failed — the
                // uploader's persistent queue makes it durable offline, so the
                // session (geo/heading/timestamps) reaches the backend either
                // way. A retry that later succeeds skips the re-archive via
                // archivedSessionIds.
                pendingSessionDraft?.let { draft -> uploadEnvScanSession(draft, reportId = null) }
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
        _uiState.value = state.copy(observedItems = keptItems, capturedPhotos = keptPhotos)
    }

    fun reset() {
        _uiState.value = SpotUiState()
        observedItemStamps.clear()
        photoStamps.clear()
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
