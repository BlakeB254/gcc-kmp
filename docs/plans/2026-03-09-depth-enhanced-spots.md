# Depth-Enhanced Spot Reports — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** On LiDAR-capable iPhones (12 Pro+), automatically capture depth data alongside spot photos to estimate debris volume and area — with zero UX change and graceful fallback on non-LiDAR devices.

**Architecture:** AVDepthData is captured alongside each photo via `AVCaptureDepthDataOutput` added to the existing `AVCaptureSession`. Depth is processed on-device into a lightweight `DepthAnalysis` struct (volume estimate, area estimate, tiny depth thumbnail). This flows through the existing `CapturedPhoto` → `SpotViewModel` → API pipeline as optional JSON metadata. Non-LiDAR devices see `depthAnalysis = null` everywhere — zero code paths change.

**Tech Stack:** Kotlin Multiplatform (commonMain/iosMain/androidMain/desktopMain), AVFoundation depth APIs, Compose Multiplatform UI, Next.js API routes, Drizzle ORM (PostgreSQL)

---

## Task 1: Add DepthAnalysis Model to Common Code

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CameraManager.kt`

**Step 1: Add the DepthAnalysis data class**

Add this **above** the existing `CapturedPhoto` class (line 6, before the `CapturedPhoto` class):

```kotlin
import kotlinx.serialization.Serializable

/**
 * Depth data captured from LiDAR-capable devices.
 * Null on devices without LiDAR/TrueDepth.
 */
@Serializable
data class DepthAnalysis(
    val hasDepth: Boolean = false,
    val estimatedVolumeCubicFt: Double? = null,
    val estimatedAreaSqFt: Double? = null,
    val maxDepthMeters: Double? = null,
    val depthConfidence: Float? = null,
    val depthMapThumbnailBase64: String? = null,
)
```

**Step 2: Add `depthAnalysis` field to `CapturedPhoto`**

The existing `CapturedPhoto` (line 9-25) becomes:

```kotlin
data class CapturedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val depthAnalysis: DepthAnalysis? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPhoto) return false
        return bytes.contentEquals(other.bytes) && width == other.width && height == other.height && depthAnalysis == other.depthAnalysis
    }
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (depthAnalysis?.hashCode() ?: 0)
        return result
    }
}
```

**Step 3: Verify build compiles**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew composeApp:compileKotlinIosArm64 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (the new field has a default value of `null`, so all existing call sites remain valid)

**Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CameraManager.kt
git commit -m "feat: add DepthAnalysis model and extend CapturedPhoto with optional depth data"
```

---

## Task 2: Add DepthAnalysis to Shared CommunityReport Model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt`

**Step 1: Add `depthAnalysis` field to CommunityReport**

After the existing `needsVerification` field (line 29) and before `createdAt` (line 30), add:

```kotlin
    val depthAnalysis: DepthAnalysis? = null,
```

Also add a new `DepthAnalysis` class to this file (or import it). Since the shared module may not see the `composeApp` module, define a parallel serializable class here:

```kotlin
@Serializable
data class DepthAnalysis(
    val hasDepth: Boolean = false,
    val estimatedVolumeCubicFt: Double? = null,
    val estimatedAreaSqFt: Double? = null,
    val maxDepthMeters: Double? = null,
    val depthConfidence: Float? = null,
    val depthMapThumbnailBase64: String? = null,
)
```

**Important:** The `DepthAnalysis` in `CommunityReport.kt` (shared module) and the one in `CameraManager.kt` (composeApp module) are separate classes with the same shape. The composeApp one lives on `CapturedPhoto` (local camera data), while the shared one lives on `CommunityReport` (API response data). They have identical fields so JSON serialization is transparent.

**Step 2: Add `depthAnalysis` field to CreateSpotRequest**

In `SpotModels.kt`, after the existing `reporterName` field (line 13), add:

```kotlin
    val depthAnalysis: DepthAnalysis? = null,
```

The `DepthAnalysis` import comes from the same package (shared model).

**Step 3: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew shared:compileKotlinIosArm64 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt
git commit -m "feat: add DepthAnalysis to CommunityReport and CreateSpotRequest models"
```

---

## Task 3: iOS Camera — Add AVCaptureDepthDataOutput for LiDAR Devices

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt`

This is the core iOS implementation. The strategy:
1. In `CameraPreviewWithAVCapture`, detect LiDAR and optionally add depth output
2. In `PhotoCaptureDelegate`, extract depth data from the captured photo
3. Process depth map on-device to compute volume/area estimates

**Step 1: Add depth-related imports**

Add these imports at the top of the file (after existing imports):

```kotlin
import platform.AVFoundation.AVCaptureDepthDataOutput
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInLiDARDepthCamera
import platform.AVFoundation.AVMediaTypeDepthData
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.get
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import kotlin.math.abs
import kotlin.math.max
```

**Step 2: Add depth processing helper**

Add this function after `resizedToMax()` (after line 71), before `PhotoCaptureDelegate`:

```kotlin
/**
 * Process raw depth data from AVCapturePhoto into a DepthAnalysis.
 * Extracts depth values, estimates ground plane, computes volume above ground.
 */
@OptIn(ExperimentalForeignApi::class)
private fun processDepthData(photo: AVCapturePhoto): DepthAnalysis? {
    val depthData = photo.depthData ?: return null
    val pixelBuffer = depthData.depthDataMap

    CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
    try {
        val width = CVPixelBufferGetWidth(pixelBuffer).toInt()
        val height = CVPixelBufferGetHeight(pixelBuffer).toInt()
        val bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer).toInt()
        val baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) ?: return null

        // Depth map is Float32 (kCVPixelFormatType_DepthFloat32)
        val floatPtr = baseAddress.reinterpret<kotlinx.cinterop.FloatVar>()
        val floatsPerRow = bytesPerRow / 4

        // Sample depth values for analysis (every 4th pixel for speed)
        val depths = mutableListOf<Float>()
        val bottomDepths = mutableListOf<Float>() // bottom 20% for ground plane

        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                val d = floatPtr[y * floatsPerRow + x]
                if (d.isFinite() && d > 0f && d < 10f) { // valid range 0-10m
                    depths.add(d)
                    if (y > height * 0.8) {
                        bottomDepths.add(d)
                    }
                }
            }
        }

        if (depths.isEmpty()) return null

        // Ground plane = median depth of bottom 20% of frame
        bottomDepths.sort()
        val groundPlane = if (bottomDepths.isNotEmpty()) {
            bottomDepths[bottomDepths.size / 2]
        } else {
            depths.sorted()[depths.size / 2]
        }

        // Calculate volume: sum of (groundPlane - depth) for pixels closer than ground
        // Each pixel represents a small area based on distance and FOV
        // iPhone LiDAR has ~60° horizontal FOV
        val hFovRad = 1.047 // ~60° in radians
        var totalVolumeM3 = 0.0
        var aboveGroundCount = 0
        var maxDepth = 0f
        var totalConfidence = 0f

        for (y in (height * 0.1).toInt() until (height * 0.9).toInt() step 4) {
            for (x in (width * 0.1).toInt() until (width * 0.9).toInt() step 4) {
                val d = floatPtr[y * floatsPerRow + x]
                if (d.isFinite() && d > 0f && d < groundPlane) {
                    val heightAboveGround = (groundPlane - d).toDouble()
                    // Pixel area at this depth (based on FOV and resolution)
                    val pixelWidthM = (d * hFovRad / width) * 4 // step=4
                    val pixelAreaM2 = pixelWidthM * pixelWidthM
                    totalVolumeM3 += heightAboveGround * pixelAreaM2
                    aboveGroundCount++
                    if (d > maxDepth) maxDepth = d
                }
            }
        }

        // Convert m³ to cubic feet (1 m³ = 35.3147 ft³)
        val volumeCuFt = totalVolumeM3 * 35.3147

        // Estimated area = number of above-ground pixels * pixel area at ground plane distance
        val groundPixelWidthM = (groundPlane * hFovRad / width) * 4
        val areaSqFt = aboveGroundCount * groundPixelWidthM * groundPixelWidthM * 10.7639 // m² to ft²

        // Average confidence (simplified — use depth accuracy)
        val confidence = if (depths.size > 0) {
            val validRatio = depths.count { abs(it - groundPlane) < 0.05f }.toFloat() / depths.size
            (1f - validRatio).coerceIn(0.3f, 0.95f)
        } else 0.5f

        return DepthAnalysis(
            hasDepth = true,
            estimatedVolumeCubicFt = if (volumeCuFt > 0.01) (volumeCuFt * 10).toInt() / 10.0 else null,
            estimatedAreaSqFt = if (areaSqFt > 0.1) (areaSqFt * 10).toInt() / 10.0 else null,
            maxDepthMeters = if (maxDepth > 0f) (maxDepth * 100).toInt() / 100.0 else null,
            depthConfidence = (confidence * 100).toInt() / 100f,
            depthMapThumbnailBase64 = null, // Phase 2: generate colored thumbnail
        )
    } finally {
        CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
    }
}
```

**Step 3: Update PhotoCaptureDelegate to extract depth**

Replace the `PhotoCaptureDelegate` class (lines 78-93) with:

```kotlin
private class PhotoCaptureDelegate(
    private val onComplete: (NSData?, DepthAnalysis?) -> Unit,
) : NSObject(), platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol {
    @Suppress("CONFLICTING_OVERLOADS")
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            onComplete(null, null)
            return
        }
        val depthAnalysis = processDepthData(didFinishProcessingPhoto)
        onComplete(didFinishProcessingPhoto.fileDataRepresentation(), depthAnalysis)
    }
}
```

**Step 4: Update captureFromPreviewSession to pass depth through**

Replace the `captureFromPreviewSession` method (lines 129-169) — the delegate callback now receives `(NSData?, DepthAnalysis?)`:

```kotlin
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun captureFromPreviewSession(
        photoOutput: AVCapturePhotoOutput,
    ): CapturedPhoto? = suspendCancellableCoroutine { continuation ->
        dispatch_async(dispatch_get_main_queue()) {
            val settings = AVCapturePhotoSettings.photoSettings()
            // Enable depth data delivery if the output supports it
            if (photoOutput.isDepthDataDeliverySupported()) {
                settings.setDepthDataDeliveryEnabled(true)
            }

            val delegate = PhotoCaptureDelegate { fileData, depthAnalysis ->
                if (fileData == null || fileData.length.toInt() == 0) {
                    continuation.resume(null)
                    return@PhotoCaptureDelegate
                }

                val image = UIImage(data = fileData).resizedToMax(1920.0)
                val jpegData: NSData? = UIImageJPEGRepresentation(image, 0.80)
                if (jpegData == null || jpegData.length.toInt() == 0) {
                    continuation.resume(null)
                    return@PhotoCaptureDelegate
                }

                val bytes = jpegData.bytes?.readBytes(jpegData.length.toInt())
                if (bytes == null) {
                    continuation.resume(null)
                    return@PhotoCaptureDelegate
                }

                val width = image.size.useContents { width.toInt() }
                val height = image.size.useContents { height.toInt() }

                continuation.resume(
                    CapturedPhoto(
                        bytes = bytes,
                        width = width,
                        height = height,
                        depthAnalysis = depthAnalysis,
                    )
                )
            }

            photoOutput.capturePhotoWithSettings(settings, delegate = delegate)
        }
    }
```

**Step 5: Update CameraPreviewWithAVCapture to use LiDAR camera when available**

Replace the device selection and depth output setup in `CameraPreviewWithAVCapture` (lines 398-462). The key changes are:
1. Prefer the LiDAR depth camera device over the generic video device
2. Add `AVCaptureDepthDataOutput` when LiDAR is available
3. Store a flag so the UI can show a "3D" indicator

```kotlin
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun CameraPreviewWithAVCapture(modifier: Modifier) {
    val captureSession = AVCaptureSession().apply {
        sessionPreset = AVCaptureSessionPresetHigh
    }

    // Prefer LiDAR depth camera, fall back to standard video camera
    val lidarDevice = AVCaptureDevice.defaultDeviceWithDeviceType(
        AVCaptureDeviceTypeBuiltInLiDARDepthCamera,
        AVMediaTypeVideo,
        position = platform.AVFoundation.AVCaptureDevicePositionBack,
    )
    val device = lidarDevice ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
    if (device == null) {
        CameraPreviewFallback(modifier)
        return
    }

    val input = try {
        AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
    } catch (_: Throwable) {
        null
    }

    if (input == null || !captureSession.canAddInput(input)) {
        CameraPreviewFallback(modifier)
        return
    }

    captureSession.addInput(input)

    // Add photo output for inline capture
    val photoOutput = AVCapturePhotoOutput()
    if (captureSession.canAddOutput(photoOutput)) {
        captureSession.addOutput(photoOutput)
    }

    // Enable depth data delivery if LiDAR is available
    if (lidarDevice != null && photoOutput.isDepthDataDeliverySupported()) {
        photoOutput.setDepthDataDeliveryEnabled(true)
    }

    // Store refs so capturePhoto() can use the live session
    CameraManager.activePhotoOutput = photoOutput
    CameraManager.activeSession = captureSession
    CameraManager.hasDepthCapability = lidarDevice != null

    androidx.compose.ui.interop.UIKitView(
        factory = {
            val previewLayer = AVCaptureVideoPreviewLayer(session = captureSession)
            previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill

            val container = UIView(frame = CGRectMake(0.0, 0.0, 400.0, 600.0))
            container.backgroundColor = platform.UIKit.UIColor.blackColor
            previewLayer.frame = container.bounds
            container.layer.addSublayer(previewLayer)

            dispatch_async(dispatch_get_main_queue()) {
                captureSession.startRunning()
            }

            container
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            val previewLayer = view.layer.sublayers
                ?.filterIsInstance<AVCaptureVideoPreviewLayer>()
                ?.firstOrNull()
            previewLayer?.frame = view.bounds
        },
        onRelease = {
            CameraManager.activePhotoOutput = null
            CameraManager.activeSession = null
            CameraManager.hasDepthCapability = false
            dispatch_async(dispatch_get_main_queue()) {
                captureSession.stopRunning()
            }
        },
    )
}
```

**Step 6: Update the companion object to include hasDepthCapability**

Replace the existing companion object (lines 257-260):

```kotlin
    companion object {
        internal var activePhotoOutput: AVCapturePhotoOutput? = null
        internal var activeSession: AVCaptureSession? = null
        internal var hasDepthCapability: Boolean = false
    }
```

**Step 7: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew composeApp:compileKotlinIosArm64 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt
git commit -m "feat: add AVCaptureDepthDataOutput for LiDAR depth capture on iOS"
```

---

## Task 4: Pass Depth Data Through SpotViewModel to API

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt`

**Step 1: Include depth analysis in the CreateSpotRequest**

In the `submitReport()` method, after building `uploadedUrls` (line 124) and before creating `CreateSpotRequest` (line 126), add logic to collect depth analyses from captured photos:

```kotlin
                // Collect depth analysis from the first photo that has it
                val depthAnalysis = state.capturedPhotos
                    .firstNotNullOfOrNull { it.depthAnalysis }
                    ?.let { depth ->
                        com.gcc.shared.model.DepthAnalysis(
                            hasDepth = depth.hasDepth,
                            estimatedVolumeCubicFt = depth.estimatedVolumeCubicFt,
                            estimatedAreaSqFt = depth.estimatedAreaSqFt,
                            maxDepthMeters = depth.maxDepthMeters,
                            depthConfidence = depth.depthConfidence,
                            depthMapThumbnailBase64 = depth.depthMapThumbnailBase64,
                        )
                    }
```

Then update the `CreateSpotRequest` constructor to include it:

```kotlin
                val request = CreateSpotRequest(
                    type = CommunityReportType.SIGHTING,
                    lat = submitLat,
                    lng = submitLng,
                    description = state.description.ifBlank { null },
                    mediaUrls = uploadedUrls.ifEmpty { null },
                    affectedRadiusMeters = state.affectedRadiusMeters,
                    reporterName = if (tokenManager.isAdmin()) "GCC" else null,
                    depthAnalysis = depthAnalysis,
                )
```

**Step 2: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew composeApp:compileKotlinIosArm64 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt
git commit -m "feat: pass depth analysis from captured photos to API via CreateSpotRequest"
```

---

## Task 5: API & Database — Accept and Store Depth Data

**Files:**
- Modify: `site/src/db/schema.ts`
- Modify: `site/src/app/api/spots/route.ts`

**Step 1: Add `depthAnalysis` column to DB schema**

In `site/src/db/schema.ts`, in the `communityReports` table definition, after the `mediaCaptions` line (line 818), add:

```typescript
  depthAnalysis: jsonb('depth_analysis'),       // LiDAR depth data (DepthAnalysis JSON)
```

**Step 2: Update spots API POST to accept and store depth data**

In `site/src/app/api/spots/route.ts`, update the body destructuring in the POST handler (line 128-137) to include:

```typescript
    const {
      type,
      lat,
      lng,
      description,
      mediaUrls,
      mediaCaptions,
      affectedRadiusMeters,
      reporterName,
      depthAnalysis,
    } = body
```

Then add `depthAnalysis` to the insert values (after `missedVerifications`, line 166):

```typescript
        depthAnalysis: depthAnalysis || null,
```

**Step 3: Update toCommunityReport to return depth data**

In the `toCommunityReport` helper function (lines 15-45), add after `needsVerification`:

```typescript
    depthAnalysis: r.depthAnalysis || null,
```

**Step 4: Verify TypeScript compiles**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc/site && npx tsc --noEmit 2>&1 | tail -10`
Expected: No errors (jsonb accepts any JSON-serializable value)

**Step 5: Commit**

```bash
git add site/src/db/schema.ts site/src/app/api/spots/route.ts
git commit -m "feat: accept and store LiDAR depth analysis in spots API"
```

---

## Task 6: UI — "3D" Badge on Photo Thumbnails

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt`

**Step 1: Add "3D" badge to thumbnail strip**

In the thumbnail strip `LazyRow` (lines 141-190), inside the `Box` that shows each thumbnail, add a "3D" badge after the remove button. After the `if (onRemovePhoto != null)` block (line 186), add:

```kotlin
                        // "3D" badge for depth-enhanced photos
                        if (photo.depthAnalysis?.hasDepth == true) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF3B82F6).copy(alpha = 0.9f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    "3D",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    ),
                                    color = Color.White,
                                )
                            }
                        }
```

Also add `import androidx.compose.ui.unit.TextUnit` and `import androidx.compose.ui.unit.TextUnitType` to the imports if not already present (these may already be available via the star import of `androidx.compose.ui.unit.dp`).

**Step 2: Add "3D" indicator to the camera preview overlay**

In the camera preview `Box` (line 62-132), add a "LiDAR Active" badge at top-right when depth is available. This requires reading the companion flag. After the photo count badge (line 131), before the closing brace of the preview Box, add:

```kotlin
            // LiDAR active indicator (iOS only — shows when depth camera is in use)
            if (capturedPhotos.any { it.depthAnalysis?.hasDepth == true } ||
                (capturedPhotos.isEmpty() && hasLiDAR())) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3B82F6).copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "LiDAR",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
```

**Step 3: Add hasLiDAR() expect/actual**

Add to `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CameraManager.kt`:

```kotlin
/** Whether the current device has LiDAR depth capability. */
expect fun hasLiDAR(): Boolean
```

Add actual implementations:

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt`:
```kotlin
actual fun hasLiDAR(): Boolean = CameraManager.hasDepthCapability
```

In `composeApp/src/androidMain/kotlin/com/gcc/app/platform/CameraManager.kt`:
```kotlin
actual fun hasLiDAR(): Boolean = false
```

In `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/CameraManager.kt`:
```kotlin
actual fun hasLiDAR(): Boolean = false
```

Then import it in CameraCapture.kt:
```kotlin
import com.gcc.app.platform.hasLiDAR
```

**Step 4: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew composeApp:compileKotlinIosArm64 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CameraManager.kt \
       composeApp/src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt \
       composeApp/src/androidMain/kotlin/com/gcc/app/platform/CameraManager.kt \
       composeApp/src/desktopMain/kotlin/com/gcc/app/platform/CameraManager.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt
git commit -m "feat: add 3D badge on depth-enhanced photo thumbnails and LiDAR indicator"
```

---

## Task 7: UI — Volume Estimate in Spot Result Screen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`

**Step 1: Show depth analysis in the ResultPhase success card**

In the `ResultPhase` composable, inside the success `GccCard` (after the AI analysis card, around line 548), add a depth analysis section:

```kotlin
                    it.depthAnalysis?.let { depth ->
                        if (depth.hasDepth) {
                            Spacer(modifier = Modifier.height(8.dp))
                            DepthDataCard(depth)
                        }
                    }
```

**Step 2: Create the DepthDataCard composable**

Add this after the `AiAnalysisCard` composable (after line 680):

```kotlin
@Composable
private fun DepthDataCard(depth: com.gcc.shared.model.DepthAnalysis) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF3B82F6))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "3D SCAN",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "LiDAR Depth Analysis",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFF3B82F6),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        depth.estimatedVolumeCubicFt?.let {
            Text(
                "Est. Volume: ~${it} cu ft",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        depth.estimatedAreaSqFt?.let {
            Text(
                "Est. Area: ~${it} sq ft",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        depth.maxDepthMeters?.let {
            Text(
                "Max Height: ${it}m",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        depth.depthConfidence?.let {
            Text(
                "Confidence: ${(it * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
        }
    }
}
```

**Step 3: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew composeApp:compileKotlinIosArm64 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt
git commit -m "feat: show LiDAR depth analysis (volume, area) in spot result screen"
```

---

## Task 8: Full Build, Archive & TestFlight Upload

**Step 1: Bump build number**

In `iosApp/iosApp/Info.plist`, increment `CFBundleVersion` from 31 to 32.

**Step 2: Build the iOS framework**

Run:
```bash
cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc && ./gradlew :composeApp:assembleXCFramework 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL (may take 15-20 minutes)

**Step 3: Archive**

Run:
```bash
cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc/iosApp && \
xcodebuild archive \
  -project iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  -archivePath build/iosApp.xcarchive \
  -destination "generic/platform=iOS" \
  CODE_SIGNING_ALLOWED=YES 2>&1 | tail -10
```
Expected: ** ARCHIVE SUCCEEDED **

**Step 4: Export**

Run:
```bash
cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc/iosApp && \
xcodebuild -exportArchive \
  -archivePath build/iosApp.xcarchive \
  -exportPath build/export \
  -exportOptionsPlist ExportOptions.plist 2>&1 | tail -10
```

**Step 5: Upload to TestFlight**

Run:
```bash
xcrun altool --upload-app \
  -f /Users/codexmetatron/CDX-DEV-PROJECTS/gcc/iosApp/build/export/iosApp.ipa \
  -t ios \
  --apiKey <KEY> --apiIssuer <ISSUER> 2>&1 | tail -5
```

Or use `xcrun notarytool` / App Store Connect API as previously used.

**Step 6: Commit build number bump**

```bash
git add iosApp/iosApp/Info.plist
git commit -m "chore: bump build to 32 — LiDAR depth-enhanced spot reports"
```
