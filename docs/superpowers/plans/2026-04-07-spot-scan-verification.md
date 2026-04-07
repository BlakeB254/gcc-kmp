# Spot Scan Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scan-grade verification (geofence, LiDAR depth, AI analysis, anti-fraud attestation) to spot reports, spot verifications, and volunteer cleanups via a shared `SpotScanCapture` composable.

**Architecture:** A new `SpotScanCapture` composable wraps the existing `CameraCapture` component with geofence enforcement, automatic depth/AI analysis, and capture attestation. It replaces raw camera usage in three screens (SpotScreen, SpotVerificationScreen, VolunteerCleanupScreen). API request models gain optional enrichment fields. No new screens or navigation changes.

**Tech Stack:** Kotlin Multiplatform (Compose Multiplatform), existing CameraManager/OnDeviceAnalyzer/GeofenceVerification/LocationProvider infrastructure.

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCapturedPhoto.kt` | Data class wrapping CapturedPhoto with scan enrichments |
| Modify | `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotVerification.kt:22-28` | Add optional depth/AI/attestation fields to `VerifySpotRequest` |
| Modify | `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt:57-65` | Add optional depth/AI fields to `StartVolunteerRequest` and `CompleteVolunteerRequest` |
| Create | `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt` | Shared composable: CameraCapture + geofence badge + AI/depth enrichment |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt:113-118,189-220` | Swap CameraPhase to use SpotScanCapture |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt` | Accept ScanCapturedPhoto, wire enriched data to CreateSpotRequest |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt:79-82,129-191` | Replace placeholder camera with SpotScanCapture |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotVerificationViewModel.kt` | Accept ScanCapturedPhoto, wire enriched data to VerifySpotRequest |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt:79-107,234-244,536-546,610-620` | Replace placeholder cameras with SpotScanCapture in 3 phases |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/VolunteerCleanupViewModel.kt` | Accept ScanCapturedPhoto, wire enriched data to Start/CompleteVolunteerRequest |

---

### Task 1: Create ScanCapturedPhoto Data Class

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCapturedPhoto.kt`

- [ ] **Step 1: Create the data class**

```kotlin
package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class ScanCapturedPhoto(
    val photoBytes: ByteArray? = null,
    val photoWidth: Int = 0,
    val photoHeight: Int = 0,
    val depthAnalysis: DepthAnalysis? = null,
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val captureLocation: Coordinates? = null,
    val attestation: CaptureAttestation? = null,
    val distanceFromSpot: Double? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanCapturedPhoto) return false
        return photoBytes?.contentEquals(other.photoBytes ?: byteArrayOf()) == true &&
            photoWidth == other.photoWidth &&
            photoHeight == other.photoHeight &&
            depthAnalysis == other.depthAnalysis
    }
    override fun hashCode(): Int {
        var result = photoBytes?.contentHashCode() ?: 0
        result = 31 * result + photoWidth
        result = 31 * result + photoHeight
        result = 31 * result + (depthAnalysis?.hashCode() ?: 0)
        return result
    }
}
```

Note: We use flat fields (`photoBytes`, `photoWidth`, `photoHeight`) instead of embedding `CapturedPhoto` directly because `CapturedPhoto` lives in `composeApp` (not `shared`) and contains `ByteArray` which is not `@Serializable`. The `SpotScanCapture` composable (in `composeApp`) will handle the conversion.

- [ ] **Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCapturedPhoto.kt
git commit -m "feat: add ScanCapturedPhoto data class for spot scan enrichment"
```

---

### Task 2: Add Optional Fields to API Request Models

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotVerification.kt:22-28`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt:57-65`

- [ ] **Step 1: Add fields to VerifySpotRequest**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotVerification.kt`, update `VerifySpotRequest`:

```kotlin
@Serializable
data class VerifySpotRequest(
    val result: SpotVerificationResult,
    val photoUrl: String,
    val verifierLat: Double? = null,
    val verifierLng: Double? = null,
    val expectedVerificationCount: Int? = null,
    val depthAnalysis: DepthAnalysis? = null,
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val captureAttestation: CaptureAttestation? = null,
)
```

- [ ] **Step 2: Add fields to StartVolunteerRequest**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt`, update `StartVolunteerRequest`:

```kotlin
@Serializable
data class StartVolunteerRequest(
    val beforeMediaUrls: List<String> = emptyList(),
    val beforeDepthAnalysis: DepthAnalysis? = null,
    val beforeAiAnalysis: OnDeviceAnalysisResult? = null,
)
```

- [ ] **Step 3: Add fields to CompleteVolunteerRequest**

In the same file, update `CompleteVolunteerRequest`:

```kotlin
@Serializable
data class CompleteVolunteerRequest(
    val afterMediaUrls: List<String> = emptyList(),
    val trashHeapMediaUrl: String? = null,
    val afterDepthAnalysis: DepthAnalysis? = null,
    val afterAiAnalysis: OnDeviceAnalysisResult? = null,
)
```

- [ ] **Step 4: Add necessary imports**

Add to `SpotVerification.kt` if not already present:

```kotlin
import com.gcc.shared.model.DepthAnalysis
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.shared.model.CaptureAttestation
```

Check if `DepthAnalysis` import is needed — it's in `CommunityReport.kt` in the same package, so it may already resolve. If not, add the import.

- [ ] **Step 5: Build to verify**

Run: `./gradlew :shared:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/SpotVerification.kt shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt
git commit -m "feat: add optional scan enrichment fields to spot API request models"
```

---

### Task 3: Create SpotScanCapture Composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt`

This is the core shared component. It wraps `CameraCapture` with geofence enforcement, AI analysis, and depth enrichment.

- [ ] **Step 1: Create the SpotScanCapture composable**

```kotlin
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
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.detectDeviceType
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.CaptureAttestation
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.DeviceType
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.compose.koinInject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val GeofenceGreen = Color(0xFF22C55E)
private val GeofenceRed = Color(0xFFEF4444)
private val GeofenceYellow = Color(0xFFEAB308)

@OptIn(ExperimentalUuidApi::class)
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
    val sessionId = remember { Uuid.random().toString() }
    var captureIndex by remember { mutableIntStateOf(0) }

    // Compute distance to spot
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

    // GPS timeout for "waiting" state
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
        // Camera preview — uses existing CameraCapture but intercepts capture
        CameraCapture(
            capturedPhotos = capturedPhotos,
            onCapture = { photo ->
                if (!canCapture) return@CameraCapture

                val idx = captureIndex++
                val location = currentLocation

                // Build attestation
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

                // Build enriched photo
                val scanPhoto = ScanCapturedPhoto(
                    photoBytes = photo.bytes,
                    photoWidth = photo.width,
                    photoHeight = photo.height,
                    depthAnalysis = photo.depthAnalysis,
                    captureLocation = location,
                    attestation = attestation,
                    distanceFromSpot = distanceToSpot,
                )

                // Run AI analysis asynchronously on first photo
                if (captureIndex == 1) {
                    scope.launch {
                        try {
                            val analysis = OnDeviceAnalyzer.analyzePhoto(photo)
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

        // Geofence proximity badge overlay
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

        // LiDAR indicator
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

// Platform-specific device info — expect/actual pattern
expect fun getPlatformDeviceModel(): String
expect fun getPlatformOsVersion(): String
```

- [ ] **Step 2: Create iOS actual implementations for platform functions**

Create `composeApp/src/iosMain/kotlin/com/gcc/app/ui/components/SpotScanCapturePlatform.kt`:

```kotlin
package com.gcc.app.ui.components

import platform.UIKit.UIDevice

actual fun getPlatformDeviceModel(): String =
    UIDevice.currentDevice.model

actual fun getPlatformOsVersion(): String =
    "iOS ${UIDevice.currentDevice.systemVersion}"
```

- [ ] **Step 3: Create Android actual implementations (stub)**

Create `composeApp/src/androidMain/kotlin/com/gcc/app/ui/components/SpotScanCapturePlatform.kt`:

```kotlin
package com.gcc.app.ui.components

import android.os.Build

actual fun getPlatformDeviceModel(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}"

actual fun getPlatformOsVersion(): String =
    "Android ${Build.VERSION.RELEASE}"
```

- [ ] **Step 4: Create desktop actual implementations (stub)**

Create `composeApp/src/desktopMain/kotlin/com/gcc/app/ui/components/SpotScanCapturePlatform.kt`:

```kotlin
package com.gcc.app.ui.components

actual fun getPlatformDeviceModel(): String = "Desktop"
actual fun getPlatformOsVersion(): String = System.getProperty("os.name") ?: "Unknown"
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt \
      composeApp/src/iosMain/kotlin/com/gcc/app/ui/components/SpotScanCapturePlatform.kt \
      composeApp/src/androidMain/kotlin/com/gcc/app/ui/components/SpotScanCapturePlatform.kt \
      composeApp/src/desktopMain/kotlin/com/gcc/app/ui/components/SpotScanCapturePlatform.kt
git commit -m "feat: add SpotScanCapture composable with geofence, LiDAR, AI pipeline"
```

---

### Task 4: Integrate SpotScanCapture into SpotScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt:113-118,189-220`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt`

- [ ] **Step 1: Update CameraPhase in SpotScreen to use SpotScanCapture**

In `SpotScreen.kt`, replace the `CameraPhase` composable (around lines 189-220) with:

```kotlin
@Composable
private fun CameraPhase(
    photos: List<CapturedPhoto>,
    spotLocation: Coordinates?,
    onScanCapture: (ScanCapturedPhoto) -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spotLocation != null) {
            SpotScanCapture(
                spotLocation = spotLocation,
                geofenceRadius = 50.0,
                maxPhotos = 3,
                capturedPhotos = photos,
                onPhotoCaptured = onScanCapture,
                onRemovePhoto = onRemovePhoto,
                modifier = Modifier.weight(1f),
            )
        } else {
            CameraCapture(
                capturedPhotos = photos,
                onCapture = { photo ->
                    onScanCapture(
                        ScanCapturedPhoto(
                            photoBytes = photo.bytes,
                            photoWidth = photo.width,
                            photoHeight = photo.height,
                            depthAnalysis = photo.depthAnalysis,
                        )
                    )
                },
                onRemovePhoto = onRemovePhoto,
                modifier = Modifier.weight(1f),
                showMiniMap = true,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        GccButton(
            text = if (photos.isNotEmpty()) "Next: Set Location" else "Skip Photo",
            onClick = onNext,
            variant = if (photos.isNotEmpty()) GccButtonVariant.PRIMARY else GccButtonVariant.OUTLINED,
        )
    }
}
```

Add imports at the top of SpotScreen.kt:
```kotlin
import com.gcc.app.ui.components.SpotScanCapture
import com.gcc.shared.model.ScanCapturedPhoto
```

- [ ] **Step 2: Update the CameraPhase invocation in the when block**

In SpotScreen.kt around lines 113-118, update the `CameraPhase` call to pass the spot location and use the new signature:

```kotlin
SpotPhase.CAMERA -> CameraPhase(
    photos = uiState.capturedPhotos,
    spotLocation = uiState.spotLocation,
    onScanCapture = { scanPhoto -> viewModel.addScanPhoto(scanPhoto) },
    onRemovePhoto = { index -> viewModel.removePhoto(index) },
    onNext = { viewModel.nextPhase() },
)
```

- [ ] **Step 3: Add addScanPhoto method to SpotViewModel**

In `SpotViewModel.kt`, find the existing `addPhoto(photo: CapturedPhoto)` method and add a companion method:

```kotlin
fun addScanPhoto(scanPhoto: ScanCapturedPhoto) {
    val photo = CapturedPhoto(
        bytes = scanPhoto.photoBytes ?: byteArrayOf(),
        width = scanPhoto.photoWidth,
        height = scanPhoto.photoHeight,
        depthAnalysis = scanPhoto.depthAnalysis,
        attestation = scanPhoto.attestation,
    )
    addPhoto(photo)

    // Store enriched scan data for API submission
    if (scanPhoto.aiAnalysis != null) {
        _uiState.value = _uiState.value.copy(
            onDeviceAnalysis = scanPhoto.aiAnalysis,
        )
    }
}
```

Check the SpotViewModel for where `onDeviceAnalysis` is stored — it likely already has a field in the UI state since SpotScreen already runs AI analysis. Verify the field name matches.

- [ ] **Step 4: Build and verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
      composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt
git commit -m "feat: integrate SpotScanCapture into SpotScreen camera phase"
```

---

### Task 5: Integrate SpotScanCapture into SpotVerificationScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt:79-82,129-191`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotVerificationViewModel.kt`

The verification screen currently has a placeholder camera UI (emoji box + capture button). We replace it with the real `SpotScanCapture`.

- [ ] **Step 1: Replace CameraPhase in SpotVerificationScreen**

Replace the `CameraPhase` composable (around lines 129-191) with:

```kotlin
@Composable
private fun CameraPhase(
    spotLocation: Coordinates?,
    capturedPhoto: CapturedPhoto?,
    onScanCapture: (ScanCapturedPhoto) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(title = "Verify This Spot")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Take a photo to show the current state of this spot.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (spotLocation != null) {
            SpotScanCapture(
                spotLocation = spotLocation,
                geofenceRadius = 30.0,
                maxPhotos = 1,
                capturedPhotos = listOfNotNull(capturedPhoto),
                onPhotoCaptured = onScanCapture,
                modifier = Modifier.weight(1f),
            )
        } else {
            // Fallback: basic capture without geofence
            CameraCapture(
                capturedPhotos = listOfNotNull(capturedPhoto),
                onCapture = { photo ->
                    onScanCapture(
                        ScanCapturedPhoto(
                            photoBytes = photo.bytes,
                            photoWidth = photo.width,
                            photoHeight = photo.height,
                            depthAnalysis = photo.depthAnalysis,
                        )
                    )
                },
                maxPhotos = 1,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
```

Add imports:
```kotlin
import com.gcc.app.ui.components.SpotScanCapture
import com.gcc.app.ui.components.CameraCapture
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.shared.model.Coordinates
```

- [ ] **Step 2: Update the CameraPhase invocation**

Around lines 79-82, update the when branch:

```kotlin
VerificationPhase.CAMERA -> CameraPhase(
    spotLocation = uiState.spotLocation,
    capturedPhoto = uiState.capturedPhoto,
    onScanCapture = { scanPhoto -> viewModel.setScanPhoto(scanPhoto) },
    onBack = onBack,
)
```

- [ ] **Step 3: Add setScanPhoto to SpotVerificationViewModel**

Add a method to handle enriched scan captures and store the scan data for API submission:

```kotlin
fun setScanPhoto(scanPhoto: ScanCapturedPhoto) {
    // Convert to CapturedPhoto for display
    val photo = CapturedPhoto(
        bytes = scanPhoto.photoBytes ?: byteArrayOf(),
        width = scanPhoto.photoWidth,
        height = scanPhoto.photoHeight,
        depthAnalysis = scanPhoto.depthAnalysis,
        attestation = scanPhoto.attestation,
    )

    // Store scan enrichments for VerifySpotRequest
    _uiState.value = _uiState.value.copy(
        capturedPhoto = photo,
        scanDepthAnalysis = scanPhoto.depthAnalysis,
        scanAiAnalysis = scanPhoto.aiAnalysis,
        scanAttestation = scanPhoto.attestation,
    )

    // Auto-advance to decision phase
    setPhoto(uploadPhotoAndGetUrl(photo))
}
```

Add the scan data fields to the ViewModel's UI state:

```kotlin
val scanDepthAnalysis: DepthAnalysis? = null,
val scanAiAnalysis: OnDeviceAnalysisResult? = null,
val scanAttestation: CaptureAttestation? = null,
```

Update the `verifySpot()` function to include enrichment in the request:

```kotlin
val request = VerifySpotRequest(
    result = result,
    photoUrl = uiState.photoUrl ?: "",
    verifierLat = location?.lat,
    verifierLng = location?.lng,
    depthAnalysis = uiState.scanDepthAnalysis,
    aiAnalysis = uiState.scanAiAnalysis,
    captureAttestation = uiState.scanAttestation,
)
```

Note: The exact ViewModel method names and UI state structure should be verified against the actual file. The key pattern is: store scan data in state, include it in the API request.

- [ ] **Step 4: Build and verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt \
      composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotVerificationViewModel.kt
git commit -m "feat: integrate SpotScanCapture into SpotVerificationScreen"
```

---

### Task 6: Integrate SpotScanCapture into VolunteerCleanupScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/VolunteerCleanupViewModel.kt`

Three phases need updating: BEFORE_PHOTOS, AFTER_PHOTOS, TRASH_HEAP_PHOTO. The current screens use placeholder camera UIs (emoji + capture button). We replace with real `SpotScanCapture`.

- [ ] **Step 1: Update BeforePhotosPhase**

In `VolunteerCleanupScreen.kt`, find `BeforePhotosPhase` composable and replace the placeholder camera section with `SpotScanCapture`:

Add `spotLocation: Coordinates?` and `onScanCapture: (ScanCapturedPhoto) -> Unit` parameters. Replace the `OutlinedButton(onClick = onCapture, ...)` capture button block with:

```kotlin
if (spotLocation != null) {
    SpotScanCapture(
        spotLocation = spotLocation,
        geofenceRadius = 30.0,
        maxPhotos = 3,
        onPhotoCaptured = onScanCapture,
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    )
} else {
    // Fallback placeholder
    OutlinedButton(
        onClick = { onScanCapture(ScanCapturedPhoto()) },
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
    ) {
        Text("\uD83D\uDCF8", style = MaterialTheme.typography.headlineMedium)
    }
}
```

- [ ] **Step 2: Update AfterPhotosPhase**

Same pattern — add `spotLocation` and `onScanCapture` parameters, replace the capture button with `SpotScanCapture`:

```kotlin
SpotScanCapture(
    spotLocation = spotLocation,
    geofenceRadius = 30.0,
    maxPhotos = 3,
    onPhotoCaptured = onScanCapture,
    modifier = Modifier
        .fillMaxWidth()
        .height(300.dp),
)
```

- [ ] **Step 3: Update TrashHeapPhase**

Same pattern but with `requireGeofence = false`:

```kotlin
SpotScanCapture(
    spotLocation = spotLocation ?: Coordinates(lng = 0.0, lat = 0.0),
    geofenceRadius = 30.0,
    maxPhotos = 1,
    requireGeofence = false,
    onPhotoCaptured = onScanCapture,
    modifier = Modifier
        .fillMaxWidth()
        .height(300.dp),
)
```

- [ ] **Step 4: Update VolunteerCleanupViewModel**

Add methods to accept `ScanCapturedPhoto` and store enrichment data:

```kotlin
private var beforeScanData: ScanCapturedPhoto? = null
private var afterScanData: ScanCapturedPhoto? = null

fun addBeforeScanPhoto(scanPhoto: ScanCapturedPhoto) {
    beforeScanData = scanPhoto
    addBeforePhoto(uploadPhotoAndGetUrl(scanPhoto))
}

fun addAfterScanPhoto(scanPhoto: ScanCapturedPhoto) {
    afterScanData = scanPhoto
    addAfterPhoto(uploadPhotoAndGetUrl(scanPhoto))
}

fun setTrashHeapScanPhoto(scanPhoto: ScanCapturedPhoto) {
    setTrashHeapUrl(uploadPhotoAndGetUrl(scanPhoto))
}
```

Update the `startCleanup()` function to include before enrichment:

```kotlin
val request = StartVolunteerRequest(
    beforeMediaUrls = uiState.beforePhotoUrls,
    beforeDepthAnalysis = beforeScanData?.depthAnalysis,
    beforeAiAnalysis = beforeScanData?.aiAnalysis,
)
```

Update the `completeCleanup()` function to include after enrichment:

```kotlin
val request = CompleteVolunteerRequest(
    afterMediaUrls = uiState.afterPhotoUrls,
    trashHeapMediaUrl = uiState.trashHeapUrl,
    afterDepthAnalysis = afterScanData?.depthAnalysis,
    afterAiAnalysis = afterScanData?.aiAnalysis,
)
```

- [ ] **Step 5: Update when block invocations**

Update the three phase invocations in the main when block to pass `spotLocation` and the new scan callbacks:

```kotlin
VolunteerPhase.BEFORE_PHOTOS -> BeforePhotosPhase(
    spotLocation = uiState.spotLocation,
    beforePhotoCount = uiState.beforePhotoUrls.size,
    isSubmitting = uiState.isSubmitting,
    error = uiState.error,
    onScanCapture = { viewModel.addBeforeScanPhoto(it) },
    onConfirmMessStillHere = { viewModel.startCleanup() },
    onAlreadyClean = { viewModel.reportAlreadyClean() },
    onBack = onBack,
)

VolunteerPhase.AFTER_PHOTOS -> AfterPhotosPhase(
    spotLocation = uiState.spotLocation,
    afterPhotoCount = uiState.afterPhotoUrls.size,
    onScanCapture = { viewModel.addAfterScanPhoto(it) },
    onNext = { viewModel.advancePhase() },
)

VolunteerPhase.TRASH_HEAP_PHOTO -> TrashHeapPhase(
    spotLocation = uiState.spotLocation,
    hasTrashHeap = uiState.trashHeapUrl != null,
    onScanCapture = { viewModel.setTrashHeapScanPhoto(it) },
    onSubmit = { viewModel.advancePhase() },
)
```

- [ ] **Step 6: Add imports**

Add at top of `VolunteerCleanupScreen.kt`:
```kotlin
import com.gcc.app.ui.components.SpotScanCapture
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.shared.model.Coordinates
```

- [ ] **Step 7: Build and verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt \
      composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/VolunteerCleanupViewModel.kt
git commit -m "feat: integrate SpotScanCapture into VolunteerCleanupScreen (3 phases)"
```

---

### Task 7: Build, Test on Simulator, Push to TestFlight

**Files:**
- Modify: `iosApp/iosApp/Info.plist` (bump build number)

- [ ] **Step 1: Full build for iOS simulator**

Run: Build and run via XcodeBuildMCP `build_run_sim` with `preferXcodebuild: true`
Expected: App launches on simulator

- [ ] **Step 2: Verify SpotScreen camera phase**

Navigate: Dashboard → Spot Report (Quick Actions) → Camera phase
Expected: Camera preview with geofence badge showing distance to current location. If within 50m, green badge. If out of range, red badge with "move closer" text.

- [ ] **Step 3: Verify SpotVerificationScreen**

Navigate: Map → tap a spot → Verify → Camera phase
Expected: Camera preview with geofence badge (30m radius). "3D" badge visible on Pro devices.

- [ ] **Step 4: Verify VolunteerCleanupScreen before/after/trash heap**

Navigate: Map → tap a spot → Volunteer to Clean → Before Photos phase
Expected: Camera preview with 30m geofence badge.
Continue through After Photos and Trash Heap phases — trash heap should have no geofence badge.

- [ ] **Step 5: Bump build number**

In `iosApp/iosApp/Info.plist`, increment `CFBundleVersion` to next number.

- [ ] **Step 6: Commit all changes**

```bash
git add -u
git commit -m "chore(ios): bump build for spot scan verification TestFlight"
git push origin main
```

- [ ] **Step 7: Push to TestFlight**

```bash
cd iosApp && bundle exec fastlane ios release
```

Expected: Kotlin framework compiles, Xcode archives, uploads to App Store Connect.
