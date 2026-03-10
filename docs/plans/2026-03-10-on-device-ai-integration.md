# On-Device AI Integration — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Integrate Apple Intelligence (iOS) and Gemini Nano / ML Kit (Android) for on-device trash analysis, duplicate prevention, capture attestation, and verification intelligence — with zero cloud dependency for core features.

**Architecture:** On-device first. Every AI feature works offline. Server refinement is optional (cross-device dedup via Qdrant embeddings, fraud cross-reference). Platform-specific implementations via Kotlin Multiplatform expect/actual pattern.

**Phases:**
- **Phase 1** (this plan): Smart Photo Analysis, Duplicate Prevention, Camera Roll Restriction, Capture Attestation
- **Phase 2** (future): AR Camera HUD, Full Verification Audit Pipeline

---

## Task 1: Restrict Camera Roll Access to Admin Only

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/VolunteerCleanupScreen.kt`

**Step 1:** In `CameraCapture.kt`, add an `allowLibraryPick: Boolean = false` parameter to the composable that shows the camera UI. Gate the "pick from library" button behind this flag.

**Step 2:** In `SpotScreen.kt`, pass `allowLibraryPick = isAdmin` (from tokenManager) so only admins see the library picker.

**Step 3:** In `VolunteerCleanupScreen.kt`, always pass `allowLibraryPick = false` — cleanup verification must always use live capture.

**Step 4:** Verify build: `./gradlew composeApp:compileKotlinIosArm64`

**Step 5:** Commit: `feat: restrict camera roll access to admin-only for anti-fraud`

---

## Task 2: Add Capture Attestation Metadata

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CameraManager.kt` (commonMain)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/CameraManager.kt`
- Modify: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/CameraManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt`

**Step 1:** Add `CaptureAttestation` data class to common CameraManager:
```kotlin
@Serializable
data class CaptureAttestation(
    val capturedAt: Long,           // epoch millis — from device clock
    val capturedLat: Double? = null, // GPS at moment of capture
    val capturedLng: Double? = null,
    val deviceModel: String,         // e.g. "iPhone 15 Pro", "Pixel 8"
    val osVersion: String,
    val sessionId: String,           // UUID generated when camera opens
    val captureIndex: Int,           // photo number in this session (1, 2, 3)
    val isLiveCapture: Boolean = true, // false if from library (admin only)
)
```

**Step 2:** Extend `CapturedPhoto` with `attestation: CaptureAttestation? = null`.

**Step 3:** In iOS CameraManager, populate attestation from `UIDevice.currentDevice` and `CLLocationManager`.

**Step 4:** In Android CameraManager, populate from `android.os.Build` and `FusedLocationProviderClient`.

**Step 5:** In desktop, return a stub attestation with `isLiveCapture = false`.

**Step 6:** Add `captureAttestation` field to `CreateSpotRequest` in SpotModels.kt.

**Step 7:** Verify build, commit: `feat: add capture attestation metadata for photo authenticity`

---

## Task 3: On-Device Photo Analysis — Common Interface

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/OnDeviceAnalyzer.kt`

**Step 1:** Define the expect interface for on-device AI analysis:
```kotlin
@Serializable
data class OnDeviceAnalysisResult(
    val trashTypes: List<String> = emptyList(),
    val estimatedWeightLbs: Double? = null,
    val severity: Int? = null,             // 1-5 scale
    val confidence: Float? = null,          // 0.0-1.0
    val suggestedTags: List<SpotTag> = emptyList(),
    val generatedDescription: String? = null,
    val imageEmbedding: List<Float>? = null, // for duplicate detection
)

expect class OnDeviceAnalyzer() {
    /** Whether on-device AI is available on this device. */
    fun isAvailable(): Boolean

    /** Analyze a photo for trash content. Returns analysis result. */
    suspend fun analyzePhoto(imageBytes: ByteArray, depthAnalysis: DepthAnalysis? = null): OnDeviceAnalysisResult?

    /** Generate an image embedding for duplicate detection. */
    suspend fun generateEmbedding(imageBytes: ByteArray): List<Float>?

    /** Compare two images for visual similarity (0.0 = different, 1.0 = identical). */
    suspend fun compareImages(imageA: ByteArray, imageB: ByteArray): Float?
}
```

**Step 2:** Verify build with stubs.

**Step 3:** Commit: `feat: add OnDeviceAnalyzer expect interface for platform AI`

---

## Task 4: iOS Implementation — Apple Intelligence + Vision

**Files:**
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/OnDeviceAnalyzer.kt`

**Step 1:** Implement `OnDeviceAnalyzer` using Apple's Vision framework:
- `VNClassifyImageRequest` for scene classification (outdoor, debris, construction, etc.)
- `VNRecognizeTextRequest` to detect any visible text (signs, labels on trash)
- `VNGenerateImageFeaturePrintRequest` for image embeddings (duplicate detection)
- `VNFeaturePrintObservation.computeDistance()` for image similarity

**Step 2:** For trash type detection, use `VNClassifyImageRequest` with the built-in taxonomy. Map Vision classifications to GCC SpotTag values:
- "garbage", "debris", "waste" → TRASH
- "leaf", "plant_material" → LEAVES
- "bottle", "glass" → GLASS
- "metal", "aluminum" → METAL
- "tire" → TIRES
- "furniture", "chair", "couch" → FURNITURE
- etc.

**Step 3:** For weight estimation, combine:
- Vision classification (type hints → density lookup table)
- Depth analysis volume (from LiDAR) × average density for detected trash type
- Fallback: area-based heuristic when no depth data

**Step 4:** For severity scoring (1-5):
- 1: Small item, single piece
- 2: Small pile, few items
- 3: Medium pile, multiple types
- 4: Large accumulation, blocking path
- 5: Hazardous or massive dumping

Use detected object count + estimated volume + classification.

**Step 5:** For description generation, use Apple Intelligence's on-device foundation model via the `Foundation.LanguageModel` API (iOS 18.4+) to generate a short description. Fallback to template: "{count} items of {types} detected, estimated {weight} lbs, severity {level}/5"

**Step 6:** Verify build, commit: `feat: iOS OnDeviceAnalyzer with Vision + Apple Intelligence`

---

## Task 5: Android Implementation — ML Kit + Gemini Nano

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/OnDeviceAnalyzer.kt`

**Step 1:** Implement `OnDeviceAnalyzer` using Google ML Kit:
- `ImageLabeling` with default model for scene/object classification
- `ObjectDetection` for bounding box detection
- Map ML Kit labels to GCC SpotTag values (similar mapping as iOS)

**Step 2:** For image embeddings, use ML Kit's `ImageLabeling` confidence vectors as a lightweight embedding. For higher quality, use TFLite MobileNet feature extractor if bundled.

**Step 3:** For image similarity, compute cosine similarity between label confidence vectors.

**Step 4:** For weight/severity estimation, use same heuristics as iOS (classification + area-based since most Android devices lack LiDAR).

**Step 5:** For description generation, check if Gemini Nano is available via `GenerativeModel` (Google AI client for Android). If available, generate description. Fallback to template.

**Step 6:** Desktop stub returns null for all methods, `isAvailable() = false`.

**Step 7:** Verify build, commit: `feat: Android OnDeviceAnalyzer with ML Kit + Gemini Nano`

---

## Task 6: Wire Analysis Into SpotViewModel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt`

**Step 1:** Add `OnDeviceAnalyzer` to Koin DI module.

**Step 2:** In SpotViewModel, after photo capture (when transitioning from CAMERA to LOCATION phase), trigger analysis in background:
```kotlin
// After photo captured, analyze in background
scope.launch {
    val result = analyzer.analyzePhoto(photo.bytes, photo.depthAnalysis)
    if (result != null) {
        _state.update { it.copy(
            aiAnalysis = result,
            suggestedTags = result.suggestedTags,
            generatedDescription = result.generatedDescription,
        ) }
    }
}
```

**Step 3:** Pre-populate the description field and tag selection with AI suggestions (user can edit/override).

**Step 4:** Include the AI analysis in the `CreateSpotRequest` submission.

**Step 5:** Verify build, commit: `feat: wire on-device analysis into spot reporting workflow`

---

## Task 7: Duplicate Spot Detection

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt`

**Step 1:** In SpotViewModel, after location is confirmed (LOCATION → DESCRIPTION transition), check for duplicates:
1. Query API for existing spots within 50m radius of the selected location
2. For each nearby spot that has photos, use `OnDeviceAnalyzer.compareImages()` to compute visual similarity
3. If any spot has similarity > 0.7, flag as potential duplicate

**Step 2:** Add duplicate detection state to SpotViewModel:
```kotlin
data class DuplicateMatch(
    val existingSpot: CommunityReport,
    val visualSimilarity: Float,
    val distanceMeters: Double,
)
```

**Step 3:** In SpotScreen, when duplicate is detected during DESCRIPTION phase, show a blocking dialog:
- Show the existing spot's photo and details
- "This looks like an existing report from {distance}m away"
- "View Existing Report" button (navigates to the existing spot)
- "Report Anyway" button with required text reason field
- If user overrides, include `duplicateOverrideReason` in CreateSpotRequest

**Step 4:** Add `duplicateOverrideReason: String? = null` to CreateSpotRequest.

**Step 5:** Verify build, commit: `feat: duplicate spot detection with visual similarity matching`

---

## Task 8: Update API & Database for New Fields

**Files:**
- Modify: `site/src/db/schema.ts`
- Modify: `site/src/app/api/spots/route.ts`

**Step 1:** Add new columns to communityReports table in schema.ts:
```typescript
captureAttestation: jsonb('capture_attestation'),  // CaptureAttestation JSON
duplicateOverrideReason: text('duplicate_override_reason'),
```

**Step 2:** Update spots POST route to accept and store:
- `captureAttestation`
- `duplicateOverrideReason`
- Populate `aiAnalysis` from the on-device result

**Step 3:** Update `toCommunityReport` helper to return new fields.

**Step 4:** Verify TypeScript compiles, commit: `feat: API accepts capture attestation and duplicate override`

---

## Task 9: UI Polish — Analysis Results in Spot Flow

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`

**Step 1:** In the DESCRIPTION phase, show an "AI Analysis" card above the description field:
- Animated "Analyzing..." state while processing
- Show detected trash types as colored chips
- Show estimated weight and severity badge
- Show confidence percentage
- Pre-filled description (editable)
- Pre-selected tags (toggleable)

**Step 2:** In the RESULT phase, enhance the existing analysis card with the on-device results merged with any server refinement.

**Step 3:** Verify build, commit: `feat: show AI analysis results in spot reporting flow`

---

## Task 10: Build Verification & Integration Test

**Step 1:** Full iOS build: `./gradlew composeApp:compileKotlinIosArm64`
**Step 2:** Full Android build: `./gradlew composeApp:compileKotlinAndroid`
**Step 3:** Verify all new expect/actual functions compile on all platforms.
**Step 4:** Bump build number to 36.
**Step 5:** Commit: `chore: bump build to 36 — on-device AI integration (Phase 1)`
