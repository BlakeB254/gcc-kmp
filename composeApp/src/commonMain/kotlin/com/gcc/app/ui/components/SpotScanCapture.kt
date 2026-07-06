package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.ArEnvScanOverlay
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.DeviceCapabilities
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.DeviceClass
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.getDeviceModel
import com.gcc.app.platform.getOsVersion
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.app.ui.theme.GccAvailable
import com.gcc.app.ui.theme.GccBlue
import com.gcc.app.ui.theme.GccError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

private val GeofenceYellow = Color(0xFFEAB308)

@Composable
fun SpotScanCapture(
    spotLocation: Coordinates,
    geofenceRadius: Double = 30.0,
    maxPhotos: Int = 3,
    requireGeofence: Boolean = true,
    onPhotoCaptured: (ScanCapturedPhoto) -> Unit,
    onRemovePhoto: ((Int) -> Unit)? = null,
    capturedPhotos: List<CapturedPhoto> = emptyList(),
    /** Externally blocked (e.g. OutdoorGate INDOOR): stops tap + auto capture. */
    captureBlocked: Boolean = false,
    /** Called when on-device AI identifies trash — position projected ~2.5m along heading. */
    onTrashObserved: (ObservedSpotItem) -> Unit = {},
    /** Zones the environment scan has credited (highlighted on the minimap). */
    scanCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Pass-through minimap trash markers. */
    trashItems: List<Coordinates> = emptyList(),
    /** Typed pass-through — per-type minimap glyphs. */
    typedTrashItems: List<ObservedSpotItem> = emptyList(),
    /** Ledger freshness pass-through for the minimap veil/wash. */
    fogCellCenters: List<Pair<Double, Double>> = emptyList(),
    staleCellCenters: List<Pair<Double, Double>> = emptyList(),
    freshCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Session cells credited at CLOSE tier — pass-through to the minimap. */
    closeCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Ledger-fresh cells whose best quality is close — pass-through to the minimap. */
    freshCloseCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Steering pass-through: minimap ring over the next area worth scanning. */
    guidanceTarget: Coordinates? = null,
    environmentScanMode: Boolean = false,
    statusText: String? = null,
    /** Two-phase guidance: DETAIL phase caption pass-through ("move closer —
     *  detail-scan the light areas"), shown alongside statusText. */
    detailPhase: Boolean = false,
    /** ARKit env-scan surface, passed through to the viewfinder. */
    arOverlay: ArEnvScanOverlay? = null,
    modifier: Modifier = Modifier,
) {
    val locationProvider = koinInject<LocationProvider>()
    val deviceCapabilities = koinInject<DeviceCapabilities>()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    val gpsHeading by locationProvider.headingDegrees.collectAsState()
    val scope = rememberCoroutineScope()

    val sessionId = remember { generateSessionId() }
    val analyzer = remember { OnDeviceAnalyzer() }
    val cameraManager = remember { CameraManager() }
    var captureIndex by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val startedLocationHere = !locationProvider.isTracking
        if (startedLocationHere) {
            locationProvider.startLocationUpdates()
        }
        onDispose {
            if (startedLocationHere) {
                locationProvider.stopLocationUpdates()
            }
        }
    }

    val distanceToSpot = remember(currentLocation, spotLocation) {
        currentLocation?.let { loc ->
            GeofenceVerification.distanceMeters(
                loc.lat, loc.lng,
                spotLocation.lat, spotLocation.lng,
            )
        }
    }
    val isWithinGeofence = distanceToSpot?.let { it <= geofenceRadius } ?: false
    val canCapture = (!requireGeofence || isWithinGeofence) && !captureBlocked
    val currentCanCapture by rememberUpdatedState(canCapture)

    var gpsWaitSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(currentLocation) {
        if (currentLocation == null) {
            gpsWaitSeconds = 0
            while (true) {
                delay(1000L)
                gpsWaitSeconds += 1
            }
        } else {
            gpsWaitSeconds = 0
        }
    }
    val gpsTimedOut = currentLocation == null && gpsWaitSeconds > 10

    // Shared by tap-capture and the automatic first scan below.
    fun handleCapture(photo: CapturedPhoto) {
        if (!currentCanCapture) return

        val idx = captureIndex
        captureIndex = captureIndex + 1
        val location = currentLocation

        // Prefer attestation from CameraManager when available
        val attestation = photo.attestation ?: CaptureAttestation(
            capturedAt = Clock.System.now().toEpochMilliseconds(),
            capturedLat = location?.lat,
            capturedLng = location?.lng,
            deviceModel = getDeviceModel(),
            osVersion = getOsVersion(),
            sessionId = sessionId,
            captureIndex = idx,
            isLiveCapture = true,
        )

        val scanPhoto = ScanCapturedPhoto(
            photoBytes = photo.bytes,
            photoWidth = photo.width,
            photoHeight = photo.height,
            depthAnalysis = photo.depthAnalysis,
            captureLocation = location,
            attestation = attestation,
            distanceFromSpot = distanceToSpot,
        )

        if (idx == 0) {
            scope.launch {
                try {
                    val analysis = analyzer.analyzePhoto(
                        imageBytes = photo.bytes,
                        depthAnalysis = photo.depthAnalysis,
                    )
                    if (analysis != null && analysis.trashTypes.isNotEmpty() && location != null) {
                        val itemPos = projectForward(location, gpsHeading, 2.5)
                        onTrashObserved(
                            ObservedSpotItem(
                                lat = itemPos.lat,
                                lng = itemPos.lng,
                                itemType = com.gcc.app.platform.TrashItemCatalog
                                    .canonicalType(analysis.trashTypes.first()),
                                severity = analysis.severity,
                                confidence = analysis.confidence,
                            )
                        )
                    }
                    onPhotoCaptured(scanPhoto.copy(aiAnalysis = analysis))
                } catch (e: Exception) {
                    DiagnosticsLog.error("vision-analyze", "first-capture analysis failed — photo kept without AI", e)
                    onPhotoCaptured(scanPhoto)
                }
            }
        } else {
            onPhotoCaptured(scanPhoto)
        }
    }

    // The scan is active the moment the screen opens: once the camera session
    // is live and the geofence allows capture, automatically take the first
    // environment scan (with depth + on-device AI) without any user action.
    // Additional items are captured by tapping them on the preview.
    var autoScanFired by remember { mutableStateOf(false) }
    LaunchedEffect(canCapture, capturedPhotos.isEmpty()) {
        if (autoScanFired || !canCapture || capturedPhotos.isNotEmpty()) return@LaunchedEffect
        delay(2500L) // let the camera session + GPS settle
        if (!currentCanCapture) return@LaunchedEffect
        // Silent frame grab — the environment scan must not fire the system
        // shutter sound on open (build 108 field report).
        val photo = cameraManager.captureFrame()
        if (photo != null) {
            autoScanFired = true
            handleCapture(photo)
        } else {
            DiagnosticsLog.error("camera", "auto-scan captureFrame() returned null")
        }
    }

    Box(modifier = modifier) {
        CameraCapture(
            capturedPhotos = capturedPhotos,
            onCapture = { handleCapture(it) },
            onRemovePhoto = onRemovePhoto,
            maxPhotos = maxPhotos,
            showMiniMap = true,
            currentLocation = currentLocation,
            gpsHeading = gpsHeading,
            cameraManager = cameraManager,
            scanCellCenters = scanCellCenters,
            trashItems = trashItems,
            typedTrashItems = typedTrashItems,
            fogCellCenters = fogCellCenters,
            staleCellCenters = staleCellCenters,
            freshCellCenters = freshCellCenters,
            closeCellCenters = closeCellCenters,
            freshCloseCellCenters = freshCloseCellCenters,
            guidanceTarget = guidanceTarget,
            environmentScanMode = environmentScanMode,
            statusText = statusText,
            detailPhase = detailPhase,
            arOverlay = arOverlay,
            modifier = Modifier.fillMaxSize(),
        )

        if (requireGeofence) {
            GeofenceBadge(
                distanceToSpot = distanceToSpot,
                isWithinGeofence = isWithinGeofence,
                gpsTimedOut = gpsTimedOut,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }

        CaptureModeBadge(
            deviceClass = deviceCapabilities.deviceClass,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        )

    }
}

@Composable
private fun GeofenceBadge(
    distanceToSpot: Double?,
    isWithinGeofence: Boolean,
    gpsTimedOut: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor: Color
    val text: String
    when {
        distanceToSpot == null && gpsTimedOut -> {
            bgColor = GeofenceYellow; text = "Enable Location Services"
        }
        distanceToSpot == null -> {
            bgColor = GeofenceYellow; text = "Waiting for GPS..."
        }
        isWithinGeofence -> {
            bgColor = GccAvailable; text = "${distanceToSpot.toInt()}m from spot"
        }
        else -> {
            bgColor = GccError; text = "${distanceToSpot.toInt()}m away — move closer"
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun CaptureModeBadge(
    deviceClass: DeviceClass,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when (deviceClass) {
        DeviceClass.PRO_SCANNER -> "LiDAR 3D" to GccBlue
        DeviceClass.SCANNER -> "AR Scan" to GccAvailable
        DeviceClass.BASIC -> "Camera" to GeofenceYellow
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

expect fun generateSessionId(): String

/** Project [distanceMeters] forward from [from] along [bearingDeg] (null bearing → stay put). */
internal fun projectForward(from: Coordinates, bearingDeg: Float?, distanceMeters: Double): Coordinates {
    if (bearingDeg == null) return from
    val rad = bearingDeg * kotlin.math.PI / 180.0
    val dLat = distanceMeters * kotlin.math.cos(rad) / 111_320.0
    val dLng = distanceMeters * kotlin.math.sin(rad) / (111_320.0 * kotlin.math.cos(from.lat * kotlin.math.PI / 180.0))
    return Coordinates(lat = from.lat + dLat, lng = from.lng + dLng)
}
