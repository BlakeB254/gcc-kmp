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
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.DeviceCapabilities
import com.gcc.app.platform.DeviceClass
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.getDeviceModel
import com.gcc.app.platform.getOsVersion
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.app.ui.screens.SpotArScreen
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
    // AR spot guidance fully replaces the capture UI while active: an
    // ARSession and the AVCapture camera session cannot share the camera,
    // so the CameraCapture subtree must leave the composition.
    var showArGuide by remember { mutableStateOf(false) }

    if (showArGuide) {
        SpotArScreen(
            spotLocation = spotLocation,
            onExit = { showArGuide = false },
            modifier = modifier,
        )
        return
    }

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
    val canCapture = !requireGeofence || isWithinGeofence
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
                    onPhotoCaptured(scanPhoto.copy(aiAnalysis = analysis))
                } catch (_: Exception) {
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
        val photo = cameraManager.capturePhoto()
        if (photo != null) {
            autoScanFired = true
            handleCapture(photo)
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

        // AR walk-around guidance entry — AR-capable devices only.
        if (deviceCapabilities.deviceClass != DeviceClass.BASIC) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GccBlue.copy(alpha = 0.9f))
                    .clickable { showArGuide = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "AR guide",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
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
