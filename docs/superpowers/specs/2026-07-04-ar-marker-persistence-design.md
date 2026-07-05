# AR Marker Persistence — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Give live AR-detected "confirmed trash" markers a durable write path to the server's existing `WorldObservation` model, so they survive app relaunch and become visible to other workers/the map — instead of vanishing the moment the AR session ends. Reuses the existing persisted-observation infrastructure end-to-end; adds no new table.

---

## 1. Background

Confirmed via direct reading: when `MarkerPromotionTracker` promotes a live detection, `ArFrameFeedback.addMarkerAt()` (`composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt:197-235`) only ever adds a SceneKit node to the local AR scene and appends to an in-memory `markerPositions: MutableList<Triple<Float,Float,Float>>` (line 78) — no network call, no persistence. The marker vanishes the moment `ArFrameFeedback` is garbage-collected (new AR session, screen re-navigation, app relaunch).

**This is a real one-way gap, not a from-scratch feature.** The read side already fully works: `WorldObservation` (`shared/.../model/WorldObservation.kt`) is a real, live Postgres-backed model (`site/src/db/schema.ts:1663-1685`, `worldObservations` table), fetched via `GccApiClient.getMapObservations()` → `RouteScanViewModel._expectedObservations` → rendered into the AR scene as `WorldMarkerKind.EXPECTED_TRASH` markers (`ArRouteScreen.kt:154-159` → `ArRouteOverlay.addWorldMarker()`). Write paths exist too (`recordWorldObservation()` in `site/src/lib/world-observations.ts`, called from the hotspot/spot-report/rectify routes) — but every existing writer is a *worker-initiated* HTTP action (submit hotspot, submit spot report). The live AR promotion event (`WorldMarkerKind.CONFIRMED_TRASH`) never calls any of them. It's a dead end specifically for this one source.

**Confirmed: the live backend for this domain is Next.js/Drizzle only** — `getMapObservations()` hits `$baseUrl/api/map/observations`, which resolves to `site/src/app/api/map/observations/route.ts` (a Next.js route), not the Ktor server (`grep` across `server/src/main/kotlin/com/gcc/server/routes/` finds no `WorldObservation` route at all). This means the fix needs **no new server table and no Ktor/Exposed work** — just a new Next.js write endpoint reusing the existing `recordWorldObservation()` function and `worldObservations` table.

## 2. Design

### Server: a new, minimal write endpoint (Next.js, not Ktor)

`sourceType` is a free-form `varchar` column with type safety living purely in a TypeScript union (`site/src/lib/world-observations.ts:37`) — adding a third value is a one-line change, no migration:

```typescript
export interface RecordObservationInput {
  sourceType: 'route_scan' | 'spot_report' | 'ar_auto_detect'
  ...
}
```

New route `site/src/app/api/observations/ar-detected/route.ts` — a **batch** POST (mirroring the coverage-events batch shape, since this will be fed by a durable client-side queue, §2 Client): accepts an array, loops calling the existing `recordWorldObservation()` once per item, mirroring the hotspot route's exact field-mapping (`site/src/app/api/scans/[id]/hotspots/route.ts:73-94`):

```typescript
sourceType: 'ar_auto_detect',
lat: marker.lat,
lng: marker.lng,
itemType: marker.label,
state: 'dirty',
capturedAt: marker.capturedAt,
```

No `mediaUrl`/`confidence`/`depthData`/`arAnchorData` — those fields are optional on `RecordObservationInput` and nothing is readily available to populate them at the point a live-AR marker gets promoted (no photo is taken; this is passive background detection, not a deliberate capture action). The existing 10m server-side grid-cell supersede logic (`gridCell()`, `site/src/lib/world-observations.ts:12-18`) already handles dedup at the data-model level; the client's existing 3m `MARKER_DEDUPE_METERS` dedup (`ArFrameFeedback.kt:67`) handles the AR-session-local case. No new dedup logic needed anywhere.

### Shared DTO + `GccApiClient` method

```kotlin
@Serializable
data class ArDetectedMarkerRequest(
    val lat: Double,
    val lng: Double,
    val label: String,
    val capturedAt: Instant,
)

suspend fun reportArDetectedMarkers(markers: List<ArDetectedMarkerRequest>) {
    postJson<Unit>("/api/observations/ar-detected", ArDetectedMarkerBatchRequest(markers))
}
```

### Client: a durable queue, mirroring `CoverageEventQueue` exactly

Same reasoning that led `CoverageEventQueue` to exist: a marker discovered with no signal (a real scenario — workers scan areas with poor connectivity) must not be silently lost. New `ArMarkerQueue` class, structurally identical to `CoverageEventQueue` (`composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt`): `Settings`-backed persistence (survives force-quit), `enqueue()`/`flush()`, single-flight guard, batch cap, position-based dequeue only on confirmed success, stays queued on failure/offline.

### iOS: bridging AR-local coordinates to real GPS

`ArFrameFeedback` has no visibility into geo-reference state (only a `sceneViewProvider: () -> ARSCNView?`); `ArRouteOverlay` has it (`refLat/refLng/refX/refY/refZ/lngScale`, set via `updateGeoReference()`, `ArRouteOverlay.kt:73-79,125-134`) and already has the forward conversion (`latLngToLocal`, `ArRouteOverlay.kt:276-280`):

```kotlin
private fun latLngToLocal(lat: Double, lng: Double): Pair<Double, Double> {
    val northMeters = (lat - refLat) * METERS_PER_DEG_LAT
    val eastMeters = (lng - refLng) * METERS_PER_DEG_LAT * lngScale
    return (refX + eastMeters) to (refZ - northMeters)
}
```

The needed inverse (local x, z → lat, lng), using the SAME `refLat/refLng/refX/refZ/lngScale` captured at the moment of the current geo reference — not recomputed, since a `CONFIRMED_TRASH` marker's raw ARKit world coordinates are only meaningful relative to whatever reference was current when it was placed:

```kotlin
private fun localToLatLng(x: Float, z: Float): Pair<Double, Double> {
    val lat = refLat + (refZ - z) / METERS_PER_DEG_LAT
    val lng = refLng + (x - refX) / (METERS_PER_DEG_LAT * lngScale)
    return lat to lng
}
```

**Event wiring mirrors the existing `sceneVoteListener` pattern exactly** (`ArFrameFeedback.kt:83,186-191`, `ArRouteOverlay.kt:101-103`, `ArRouteScreen.kt:114`) — a plain nullable callback set once by the screen, invoked cross-thread via `dispatch_async`, not a StateFlow.

**The label must travel with the coordinates.** `addMarkerAt(x, y, z, kind: WorldMarkerKind)` today carries no item-type information at all — only `WorldMarkerKind` (`CONFIRMED_TRASH`/`EXPECTED_TRASH`). But `analyzeTick()` already has `trashLabel: String` in scope at the exact point it calls `addMarkerAt` for a `CONFIRMED_TRASH` promotion (`ArFrameFeedback.kt:137-182`) — that's the value that needs to reach the persisted observation's `itemType`. So `addMarkerAt` gains a `label: String` parameter (used only by the listener; `EXPECTED_TRASH`'s existing call site can pass its own kind-appropriate label or an empty string, since that path is never persisted):

- `ArFrameFeedback` gains `var markerPlacedListener: ((x: Float, y: Float, z: Float, label: String) -> Unit)? = null`, invoked with the local `x, y, z` and the tick's `trashLabel` right after the dedupe check succeeds in `addMarkerAt()` (`ArFrameFeedback.kt:203-205` area) — only for `WorldMarkerKind.CONFIRMED_TRASH` (never `EXPECTED_TRASH`, which already came *from* the server; re-persisting it would create a spurious duplicate observation).
- `ArRouteOverlay` (commonMain `expect fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String) -> Unit)?)`, iOS `actual`) forwards, converting `x, z` via `localToLatLng` before calling the caller's callback with real lat/lng + the same label.
- `ArRouteScreen.kt` wires it next to the existing `setSceneVoteListener` call: `overlay.setMarkerPlacedListener { lat, lng, label -> viewModel.onMarkerPlaced(lat, lng, label) }`, torn down (`null`) alongside the existing teardown.
- `RouteScanViewModel.onMarkerPlaced(lat, lng, label)` enqueues into the new `arMarkerQueue`, mirroring `onSceneVote`'s one-line forwarding shape.

### Flush triggering — reuse the existing 30s timer, don't add a second one

`CoverageEventQueue` is flushed from exactly 3 sites in `RouteScanViewModel.kt`: a 30-second periodic timer job (`startLocationTracking()`, lines 929-937), `completeScan()` (line 786), and `onCleared()` (line 1186). The new `arMarkerQueue` flushes from the SAME 3 sites — no second timer, just an additional `arMarkerQueue.flush()` call alongside each existing `coverageEventQueue.flush()` call.

## 3. Test coverage

- **`ArMarkerQueue`**: fully testable, mirroring `CoverageEventQueueTest`'s existing pattern exactly (enqueue-then-flush round-trip, persistence-survives-recreation via `MemorySettings`, concurrent-flush-calls-result-in-one-submit-call, failed-flush-keeps-items-queued).
- **`localToLatLng`**: a pure math function, testable in isolation — verify it's the exact algebraic inverse of `latLngToLocal` (round-trip: `localToLatLng(latLngToLocal(lat, lng))` should return the original `lat, lng` within floating-point tolerance).
- **Server route**: testable the same way Task 1 of the crash-telemetry work was (request-validation-only, since this codebase has no Testcontainers setup; actual `recordWorldObservation()` DB behavior is already covered wherever the existing hotspot route calls it) — or, since this is a Next.js/TypeScript route rather than Ktor, verified via `npx tsc --noEmit` plus careful reading, matching how other `site/` changes have been verified this session.
- **`ArFrameFeedback`'s new listener wiring**: iOS-only Kotlin/Native, not unit-testable in isolation — verified by careful reading only, consistent with this file's existing (zero) test coverage.

**Caught during self-review**: an earlier draft of this design specified `markerPlacedListener`/`addMarkerAt` without a label parameter at all — carrying only local coordinates through the whole chain, with no way to populate the persisted observation's `itemType`. Fixed by threading `trashLabel` (already in scope at the `analyzeTick()` call site) through `addMarkerAt`, the listener, and the overlay's forwarding wrapper.

## 4. Out of scope

- Persisting `EXPECTED_TRASH` markers — they already came from the server; this would create spurious duplicate observations.
- Any change to the client-side 3m dedup (`MARKER_DEDUPE_METERS`) or the server-side 10m grid-cell dedup (`gridCell`) — both already exist and are sufficient.
- Photo/media capture for AR-auto-detected markers — no photo is available at the point of passive detection; `mediaUrl` stays unset for this `sourceType`.
- Android's detection pipeline — same platform-scoping note as the checkmark-confirmation work: this whole feature lives in code that's currently iOS-only (`ArFrameFeedback`), and the new `ArRouteOverlay`/`ArFrameFeedback` pieces will need Android equivalents whenever that separately-tracked, paused work lands — no changes needed here for that to happen, since the queue/DTO/server pieces are all commonMain/backend-agnostic already.
- Confidence score / severity on AR-auto-detected observations — `RecordObservationInput.confidence` stays unset; nothing readily available to populate it without new plumbing, and it's optional.
