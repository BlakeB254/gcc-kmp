package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.detectDeviceType
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.DeviceType
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.app.ui.theme.GccBlue
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

private val GeofenceGreen = Color(0xFF22C55E)
private val GeofenceRed = Color(0xFFEF4444)
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
    val currentLocation by locationProvider.currentLocation.collectAsState()
    val scope = rememberCoroutineScope()

    val deviceType = remember { detectDeviceType() }
    val sessionId = remember { generateSessionId() }
    var captureIndex by remember { mutableStateOf(0) }

    val distanceToSpot = remember(currentLocation) {
        currentLocation?.let { loc ->
            GeofenceVerification.distanceMeters(
                loc.lat, loc.lng,
                spotLocation.lat, spotLocation.lng,
            )
        }
    }
    val isWithinGeofence = distanceToSpot?.let { it <= geofenceRadius } ?: false
    val canCapture = !requireGeofence || isWithinGeofence

    var gpsWaitStarted by remember { mutableStateOf(Clock.System.now()) }
    val gpsTimedOut = remember(currentLocation) {
        if (currentLocation != null) {
            gpsWaitStarted = Clock.System.now()
            false
        } else {
            (Clock.System.now() - gpsWaitStarted).inWholeSeconds > 10
        }
    }

    Box(modifier = modifier) {
        CameraCapture(
            capturedPhotos = capturedPhotos,
            onCapture = { photo ->
                if (!canCapture) return@CameraCapture

                val idx = captureIndex
                captureIndex = captureIndex + 1
                val location = currentLocation

                val attestation = CaptureAttestation(
                    capturedAt = Clock.System.now().toEpochMilliseconds(),
                    capturedLat = location?.lat,
                    capturedLng = location?.lng,
                    deviceModel = getPlatformDeviceModel(),
                    osVersion = getPlatformOsVersion(),
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
                            val analyzer = OnDeviceAnalyzer()
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
            },
            onRemovePhoto = onRemovePhoto,
            maxPhotos = maxPhotos,
            showMiniMap = true,
            currentLocation = currentLocation,
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

        if (deviceType == DeviceType.AR) {
            LidarBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            )
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
    val (bgColor, text) = when {
        distanceToSpot == null && gpsTimedOut -> GeofenceYellow to "Enable Location Services"
        distanceToSpot == null -> GeofenceYellow to "Waiting for GPS..."
        isWithinGeofence -> GeofenceGreen to "${distanceToSpot.toInt()}m from spot"
        else -> GeofenceRed to "${distanceToSpot.toInt()}m away — move closer"
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
private fun LidarBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GccBlue.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "3D",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

expect fun getPlatformDeviceModel(): String
expect fun getPlatformOsVersion(): String
expect fun generateSessionId(): String
