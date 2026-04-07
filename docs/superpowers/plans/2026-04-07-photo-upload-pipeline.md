# Photo Upload Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire real photo upload into SpotVerificationScreen and VolunteerCleanupScreen so photos are actually uploaded to the server instead of passing placeholder URL strings.

**Architecture:** Store `CapturedPhoto` objects (raw bytes) in ViewModel state during capture. At submission time, batch-upload bytes via the existing `apiClient.uploadSpotPhotos()` endpoint, then use the returned URLs in API requests. This matches how SpotScreen already works.

**Tech Stack:** Kotlin Multiplatform (Compose Multiplatform), existing GccApiClient multipart upload, CapturedPhoto/ScanCapturedPhoto data classes.

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotVerificationViewModel.kt` | Add capturedPhoto bytes storage, upload at submit |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt` | Remove URL parameter from setScanPhoto call |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/VolunteerCleanupViewModel.kt` | Add captured photo lists, upload at start/complete |
| Modify | `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt` | Use bytes-based photo count |

---

### Task 1: Wire Photo Upload in SpotVerificationViewModel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotVerificationViewModel.kt`

- [ ] **Step 1: Add CapturedPhoto import and state field**

Add import at the top of the file:

```kotlin
import com.gcc.app.platform.CapturedPhoto
```

In `SpotVerificationUiState`, add a `capturedPhoto` field. Keep `photoUrl` for the uploaded URL (set at submit time):

```kotlin
data class SpotVerificationUiState(
    val reportId: Int = 0,
    val phase: VerificationPhase = VerificationPhase.CAMERA,
    val capturedPhoto: CapturedPhoto? = null,
    val photoUrl: String? = null,
    val pointsAwarded: Int = 0,
    val spotResolved: Boolean = false,
    val result: SpotVerificationResult? = null,
    val error: String? = null,
    val isSubmitting: Boolean = false,
    val verifierLat: Double? = null,
    val verifierLng: Double? = null,
    val expectedVerificationCount: Int = 0,
    val isLoadingSpot: Boolean = true,
    val spotCoordinates: Coordinates? = null,
    val scanDepthAnalysis: DepthAnalysis? = null,
    val scanAiAnalysis: OnDeviceAnalysisResult? = null,
    val scanAttestation: CaptureAttestation? = null,
)
```

- [ ] **Step 2: Update setScanPhoto to remove URL parameter and store bytes**

Replace the existing `setPhoto` and `setScanPhoto` methods:

```kotlin
fun setScanPhoto(scanPhoto: ScanCapturedPhoto) {
    val photo = CapturedPhoto(
        bytes = scanPhoto.photoBytes ?: byteArrayOf(),
        width = scanPhoto.photoWidth,
        height = scanPhoto.photoHeight,
        depthAnalysis = scanPhoto.depthAnalysis,
        attestation = scanPhoto.attestation,
    )
    _uiState.update {
        it.copy(
            capturedPhoto = photo,
            scanDepthAnalysis = scanPhoto.depthAnalysis,
            scanAiAnalysis = scanPhoto.aiAnalysis,
            scanAttestation = scanPhoto.attestation,
            phase = VerificationPhase.DECISION,
        )
    }
}
```

Remove the old `setPhoto(url: String)` method — it's no longer needed.

- [ ] **Step 3: Update submitVerification to upload bytes then submit**

Replace the existing `submitVerification` method:

```kotlin
fun submitVerification(result: SpotVerificationResult) {
    val state = _uiState.value
    val photo = state.capturedPhoto ?: return

    _uiState.update { it.copy(
        phase = VerificationPhase.SUBMITTING,
        isSubmitting = true,
        result = result,
        error = null,
    ) }

    scope.launch {
        try {
            // Upload photo bytes
            val urls = apiClient.uploadSpotPhotos(listOf(photo.bytes))
            val photoUrl = urls.firstOrNull()
                ?: throw Exception("No URL returned from upload")

            // Submit verification with real URL
            val response = apiClient.verifySpot(
                state.reportId,
                VerifySpotRequest(
                    result = result,
                    photoUrl = photoUrl,
                    verifierLat = state.verifierLat,
                    verifierLng = state.verifierLng,
                    expectedVerificationCount = state.expectedVerificationCount,
                    depthAnalysis = state.scanDepthAnalysis,
                    aiAnalysis = state.scanAiAnalysis,
                    captureAttestation = state.scanAttestation,
                ),
            )
            _uiState.update { it.copy(
                phase = VerificationPhase.COMPLETE,
                photoUrl = photoUrl,
                pointsAwarded = response.pointsAwarded,
                spotResolved = response.spotResolved,
                isSubmitting = false,
            ) }
        } catch (e: Exception) {
            _uiState.update { it.copy(
                phase = VerificationPhase.DECISION,
                error = e.message ?: "Verification failed",
                isSubmitting = false,
            ) }
        }
    }
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotVerificationViewModel.kt
git commit -m "feat: wire real photo upload in SpotVerificationViewModel"
```

---

### Task 2: Update SpotVerificationScreen Call Site

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt`

- [ ] **Step 1: Remove URL parameter from setScanPhoto call**

Find the `VerificationPhase.CAMERA` when branch (around line 97-103). Change from:

```kotlin
onScanCapture = { scanPhoto ->
    viewModel.setScanPhoto(
        scanPhoto,
        "verify_${reportId}_${Clock.System.now().epochSeconds}.jpg",
    )
},
```

To:

```kotlin
onScanCapture = { scanPhoto ->
    viewModel.setScanPhoto(scanPhoto)
},
```

- [ ] **Step 2: Remove unused Clock import if no longer needed**

Check if `Clock` is used elsewhere in the file. If `Clock.System.now()` was only used for the placeholder URL, remove the import:

```kotlin
// Remove if unused:
import kotlinx.datetime.Clock
```

Search the file for other `Clock` references before removing.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt
git commit -m "feat: remove placeholder URL from SpotVerificationScreen scan capture"
```

---

### Task 3: Wire Photo Upload in VolunteerCleanupViewModel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/VolunteerCleanupViewModel.kt`

- [ ] **Step 1: Add CapturedPhoto import and state fields**

Add import:

```kotlin
import com.gcc.app.platform.CapturedPhoto
```

Add captured photo lists to `VolunteerCleanupUiState`:

```kotlin
data class VolunteerCleanupUiState(
    val reportId: Int = 0,
    val cleanup: VolunteerCleanup? = null,
    val elapsedSeconds: Long = 0,
    val capturedBeforePhotos: List<CapturedPhoto> = emptyList(),
    val capturedAfterPhotos: List<CapturedPhoto> = emptyList(),
    val capturedTrashHeapPhoto: CapturedPhoto? = null,
    val beforePhotoUrls: List<String> = emptyList(),
    val afterPhotoUrls: List<String> = emptyList(),
    val trashHeapUrl: String? = null,
    val isSubmitting: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null,
    val phase: VolunteerPhase = VolunteerPhase.BEFORE_PHOTOS,
    val pointsAwarded: Int = 0,
    val spotLocation: Coordinates? = null,
)
```

- [ ] **Step 2: Update scan photo methods to store bytes instead of placeholder URLs**

Replace the three scan photo methods:

```kotlin
fun addBeforeScanPhoto(scanPhoto: ScanCapturedPhoto) {
    beforeScanData = scanPhoto
    val photo = CapturedPhoto(
        bytes = scanPhoto.photoBytes ?: byteArrayOf(),
        width = scanPhoto.photoWidth,
        height = scanPhoto.photoHeight,
        depthAnalysis = scanPhoto.depthAnalysis,
        attestation = scanPhoto.attestation,
    )
    _uiState.value = _uiState.value.copy(
        capturedBeforePhotos = _uiState.value.capturedBeforePhotos + photo,
    )
}

fun addAfterScanPhoto(scanPhoto: ScanCapturedPhoto) {
    afterScanData = scanPhoto
    val photo = CapturedPhoto(
        bytes = scanPhoto.photoBytes ?: byteArrayOf(),
        width = scanPhoto.photoWidth,
        height = scanPhoto.photoHeight,
        depthAnalysis = scanPhoto.depthAnalysis,
        attestation = scanPhoto.attestation,
    )
    _uiState.value = _uiState.value.copy(
        capturedAfterPhotos = _uiState.value.capturedAfterPhotos + photo,
    )
}

fun setTrashHeapScanPhoto(scanPhoto: ScanCapturedPhoto) {
    trashHeapScanData = scanPhoto
    val photo = CapturedPhoto(
        bytes = scanPhoto.photoBytes ?: byteArrayOf(),
        width = scanPhoto.photoWidth,
        height = scanPhoto.photoHeight,
        depthAnalysis = scanPhoto.depthAnalysis,
        attestation = scanPhoto.attestation,
    )
    _uiState.value = _uiState.value.copy(capturedTrashHeapPhoto = photo)
}
```

- [ ] **Step 3: Update startCleanup to upload before photos**

Replace the `startCleanup()` method:

```kotlin
fun startCleanup() {
    val state = _uiState.value
    scope.launch {
        _uiState.value = state.copy(isSubmitting = true, error = null)
        try {
            // Upload before photos
            val beforeUrls = if (state.capturedBeforePhotos.isNotEmpty()) {
                apiClient.uploadSpotPhotos(state.capturedBeforePhotos.map { it.bytes })
            } else {
                emptyList()
            }

            val request = StartVolunteerRequest(
                beforeMediaUrls = beforeUrls,
                beforeDepthAnalysis = beforeScanData?.depthAnalysis,
                beforeAiAnalysis = beforeScanData?.aiAnalysis,
            )
            val cleanup = apiClient.startVolunteerCleanup(state.reportId, request)
            _uiState.value = _uiState.value.copy(
                cleanup = cleanup,
                beforePhotoUrls = beforeUrls,
                phase = VolunteerPhase.IN_PROGRESS,
                isSubmitting = false,
            )
            startTimer()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isSubmitting = false,
                error = e.message ?: "Failed to start cleanup",
            )
        }
    }
}
```

- [ ] **Step 4: Update completeCleanup to upload after photos and trash heap**

Replace the `completeCleanup()` method:

```kotlin
private fun completeCleanup() {
    val state = _uiState.value
    scope.launch {
        _uiState.value = state.copy(
            phase = VolunteerPhase.SUBMITTING,
            isSubmitting = true,
            error = null,
        )
        try {
            // Upload after photos
            val afterUrls = if (state.capturedAfterPhotos.isNotEmpty()) {
                apiClient.uploadSpotPhotos(state.capturedAfterPhotos.map { it.bytes })
            } else {
                emptyList()
            }

            // Upload trash heap photo
            val trashHeapUrl = state.capturedTrashHeapPhoto?.let { photo ->
                val urls = apiClient.uploadSpotPhotos(listOf(photo.bytes))
                urls.firstOrNull()
            }

            val request = CompleteVolunteerRequest(
                afterMediaUrls = afterUrls,
                trashHeapMediaUrl = trashHeapUrl,
                afterDepthAnalysis = afterScanData?.depthAnalysis,
                afterAiAnalysis = afterScanData?.aiAnalysis,
            )
            val cleanup = apiClient.completeVolunteerCleanup(state.reportId, request)
            _uiState.value = _uiState.value.copy(
                cleanup = cleanup,
                afterPhotoUrls = afterUrls,
                trashHeapUrl = trashHeapUrl,
                isSubmitting = false,
                isCompleted = true,
                pointsAwarded = cleanup.pointsAwarded,
                phase = VolunteerPhase.COMPLETE,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isSubmitting = false,
                phase = VolunteerPhase.TRASH_HEAP_PHOTO,
                error = e.message ?: "Failed to complete cleanup",
            )
        }
    }
}
```

- [ ] **Step 5: Update reportAlreadyClean to upload before photos**

Replace the `reportAlreadyClean()` method:

```kotlin
fun reportAlreadyClean() {
    val state = _uiState.value
    scope.launch {
        _uiState.value = state.copy(
            isSubmitting = true,
            error = null,
            phase = VolunteerPhase.SUBMITTING,
        )
        try {
            // Upload before photos
            val beforeUrls = if (state.capturedBeforePhotos.isNotEmpty()) {
                apiClient.uploadSpotPhotos(state.capturedBeforePhotos.map { it.bytes })
            } else {
                emptyList()
            }

            val cleanup = apiClient.reportSpotAlreadyClean(
                state.reportId,
                beforeUrls,
            )
            _uiState.value = _uiState.value.copy(
                cleanup = cleanup,
                beforePhotoUrls = beforeUrls,
                isSubmitting = false,
                isCompleted = true,
                pointsAwarded = cleanup.pointsAwarded,
                phase = VolunteerPhase.ALREADY_CLEAN_COMPLETE,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isSubmitting = false,
                phase = VolunteerPhase.BEFORE_PHOTOS,
                error = e.message ?: "Failed to report spot as clean",
            )
        }
    }
}
```

- [ ] **Step 6: Build to verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/VolunteerCleanupViewModel.kt
git commit -m "feat: wire real photo upload in VolunteerCleanupViewModel"
```

---

### Task 4: Update VolunteerCleanupScreen Photo Counts

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt`

- [ ] **Step 1: Update BEFORE_PHOTOS phase to use captured photo count**

Find the `VolunteerPhase.BEFORE_PHOTOS` when branch (around line 80-90). Change:

```kotlin
VolunteerPhase.BEFORE_PHOTOS -> BeforePhotosPhase(
    beforePhotoCount = uiState.capturedBeforePhotos.size,
    isSubmitting = uiState.isSubmitting,
    error = uiState.error,
    onCapture = { viewModel.addBeforePhoto("before_${uiState.capturedBeforePhotos.size + 1}.jpg") },
    spotLocation = uiState.spotLocation,
    onScanCapture = { viewModel.addBeforeScanPhoto(it) },
    onConfirmMessStillHere = { viewModel.startCleanup() },
    onAlreadyClean = { viewModel.reportAlreadyClean() },
    onBack = onBack,
)
```

Note: `beforePhotoCount` now uses `capturedBeforePhotos.size` instead of `beforePhotoUrls.size`. The `onCapture` fallback still uses a placeholder (for non-scan path), but the scan path (`onScanCapture`) now stores real bytes.

- [ ] **Step 2: Update AFTER_PHOTOS phase to use captured photo count**

Find the `VolunteerPhase.AFTER_PHOTOS` when branch (around line 100-106). Change:

```kotlin
VolunteerPhase.AFTER_PHOTOS -> AfterPhotosPhase(
    afterPhotoCount = uiState.capturedAfterPhotos.size,
    onCapture = { viewModel.addAfterPhoto("after_${uiState.capturedAfterPhotos.size + 1}.jpg") },
    spotLocation = uiState.spotLocation,
    onScanCapture = { viewModel.addAfterScanPhoto(it) },
    onNext = { viewModel.advancePhase() },
)
```

- [ ] **Step 3: Update TRASH_HEAP_PHOTO phase**

Find the `VolunteerPhase.TRASH_HEAP_PHOTO` when branch (around line 108-114). Change:

```kotlin
VolunteerPhase.TRASH_HEAP_PHOTO -> TrashHeapPhase(
    hasTrashHeap = uiState.capturedTrashHeapPhoto != null,
    onCapture = { viewModel.setTrashHeapUrl("trash_heap.jpg") },
    spotLocation = uiState.spotLocation,
    onScanCapture = { viewModel.setTrashHeapScanPhoto(it) },
    onSubmit = { viewModel.advancePhase() },
)
```

Note: `hasTrashHeap` now checks `capturedTrashHeapPhoto != null` instead of `trashHeapUrl != null`.

- [ ] **Step 4: Build to verify**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt
git commit -m "feat: use captured photo counts in VolunteerCleanupScreen"
```

---

### Task 5: Build, Verify, Push

- [ ] **Step 1: Full iOS simulator build**

Build and run via XcodeBuildMCP `build_run_sim` with `preferXcodebuild: true`.
Expected: App launches on simulator.

- [ ] **Step 2: Verify SpotVerificationScreen uploads**

Navigate: Map → tap a spot → Verify → capture photo → choose Still Dirty or Already Clean.
Expected: Submission shows spinner ("Submitting..."), then completes. No placeholder URLs in the request.

- [ ] **Step 3: Verify VolunteerCleanupScreen uploads**

Navigate: Map → tap a spot → Volunteer to Clean → capture before photos → Confirm Mess → wait → capture after photos → capture trash heap → submit.
Expected: Each submit step shows spinner during upload, then proceeds. No placeholder URLs.

- [ ] **Step 4: Commit and push**

```bash
git push origin main
```
