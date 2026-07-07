# Pillar 3 — Shape-Accurate Landmark Footprints: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Landmarks (and sized trash) persist with physical width + orientation and render on the main map as true-scale oriented footprints at close zoom; heatmap stays the far-zoom story. Spec: world-scanning v2 Pillar 3.

**No schema migration:** `world_observations.attributes` jsonb already exists (schema.ts:1717) and supersede-merge already carries attributes forward.

## Global Constraints
- Gates: Kotlin `./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`; server `cd site && npx tsc --noEmit`. No Swift changes expected (pbxproj untouched).
- Wire fields: defaults required (old clients/state must decode). `Coordinates(lng, lat)` named args.
- Map layers: z-order = declaration order; new footprint layer gated on its data being non-empty (cleanup-round convention) and zoom-gated ≥ 17 via the existing interpolate/step patterns in GccMap.ios.kt.
- Footprint geometry approximations (v1, documented): vegetation (tree/bush) → circle (12-gon) of diameter widthMeters; linear/built types (fence, gate, mattress, debris_pile, default) → oriented rectangle width × depth where depth = attributes.depthMeters ?: widthMeters × 0.4; orientation = yawDegrees (bearing of the observed face). Trash types with estimatedWidthMeters use the same builder.
- Never regress pins/glyphs: footprints render UNDER the existing glyph/pin layers as a translucent fill + thin outline.

### Task 1: Capture width + yaw at registration (client)
**Files:** `composeApp/src/iosMain/.../ArEnvScan.kt`, `composeApp/src/commonMain/.../ArEnvScan.kt` (listener signature), `shared/.../model/SpotModels.kt`, SpotScreen listener wiring
- [ ] `ObservedSpotItem` += `yawDegrees: Double? = null` (LAST param; estimatedWidthMeters already exists).
- [ ] `registerElementIfAny` (iosMain): compute widthMeters via the existing `physicalWidthMeters(bboxWidthFrac, distance)` trash-path helper (saliency bbox width is already in scope); yawDegrees = geo bearing from camera position to hit world position (camera pos via nativeSurface/cameraPosition or frame camera transform; AR world is gravityAndHeading-aligned so bearing = atan2(dx, -dz) in degrees normalized 0-360 — reuse/extend ArGeoMath if a helper fits). Extend `setElementObservedListener` signature with `widthMeters: Double?, yawDegrees: Double?` (expect + all actuals + SpotScreen listener) → ObservedSpotItem fields.
- [ ] Trash path: `TrashCandidate` already has estimatedWidthMeters; where confirmed candidates become ObservedSpotItem (SpotScreen confirm flow), also set yawDegrees the same way (candidate creation site has hit + camera pos — add yaw to TrashCandidate with default null and plumb).
- [ ] isFinite guards on all new doubles at creation. Gates + commit `feat(footprints): capture width + yaw for landmarks and sized trash`.

### Task 2: Persist + serve (server) and wire read model
**Files:** find where observedItems → world_observations rows are written (grep `observedItems` in site/src — spots route / world-observations lib), `site/src/lib/world-observations.ts`, `/api/map/observations` route, `shared/.../model/WorldObservation.kt`
- [ ] Write path: attributes payload merges `{widthMeters?, yawDegrees?}` from the incoming item (only when finite; keep existing attribute keys untouched). Verify supersede-merge carries them (read the merge fn; extend if it whitelists keys).
- [ ] Read path: observations API response maps attributes → flat `widthMeters`/`yawDegrees` per observation (nullable). `WorldObservation` model += `widthMeters: Double? = null, yawDegrees: Double? = null` (LAST params).
- [ ] Wire-compat test (composeApp commonTest): legacy JSON decodes with nulls.
- [ ] tsc + Kotlin gates + commit `feat(footprints): persist and serve observation extents`.

### Task 3: Footprint map layer (client)
**Files:** `composeApp/.../ui/map/GeoJsonLayers.kt`, `composeApp/.../ui/map/GccMap.ios.kt`, `MapFullScreen.kt` (already passes observations?—verify source), minimap optional (SKIP v1)
- [ ] `buildObservationFootprintsGeoJson(observations, centerLat)`: for each observation with widthMeters != null → polygon ring per the geometry table (circle 12-gon for vegetation domain; oriented rect otherwise, yaw default 0 when null); feature props: itemType, domain, state. Pure-Kotlin unit test in composeApp commonTest (ring closure, orientation math: yaw 90° rect's long axis runs N-S vs E-W — assert vertex bounds).
- [ ] GccMap.ios: new gated layer group BEFORE the observation pin/glyph layers — translucent FillLayer (domain-tinted: vegetation green 0.18, construction grey 0.18, litter orange 0.20) + LineLayer outline 0.5px, both zoom-gated ≥17 (opacity interpolate 16.5→0, 17→full, matching existing zoom-gate idioms).
- [ ] Wherever the main map fetches observations (MapFullScreen / GccMap params — trace `getMapObservations` consumers) ensure widthMeters/yawDegrees flow into the layer builder.
- [ ] Gates + commit `feat(footprints): true-scale oriented footprints at close zoom`.

### Task 4: Sweep + PR
- [ ] Full gates incl. xcodebuild sim; 2-angle /simplify pass; PR (merge = Blake's word) with before/after description + geometry approximation table + deferred (minimap footprints, depthMeters capture, ARGeoAnchor precision dependency noted).
