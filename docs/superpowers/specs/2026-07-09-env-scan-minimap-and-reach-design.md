# Environment Scan — Minimap Overhaul & Honest Coverage Reach

**Date:** 2026-07-09 · **Status:** Investigation complete, design NOT approved
**Scope:** Spec 2 of the scan/map series (see Spec 1's Decomposition)

## Why

Field report (Blake, 2026-07-09), verbatim:

> "The minimap is terrible, it's too dark to see, can't see the user position dot, the
> cone, or the map tile beneath it. Feels like it's not really moving with the user
> correctly and definitely not using the cone to shade in the areas captured either 'seen'
> or detailed scan."

> "When I walk down the street and I turn my camera across the street to show the houses
> across the street, how come it's not really registering those areas? It's like the
> furthest my env scans went is to the street but very rarely registering across it."

> "That's fine then if it's based on the ray technology, but make sure that's displayed in
> the minimap during a scan. The current environment scan minimap needs a massive overhaul."

## Findings (verified in code)

### F1. Cross-street coverage is structurally impossible

Coverage is credited **only where a raycast lands**. `CoverageRayFan.cast`
(`composeApp/src/iosMain/.../CoverageRayFan.kt:73-85`) casts a 3×5 image-space fan and
drops any sample that returns no hit:

```kotlin
val hit = raycastPoint(surface, frame, ix, iy) ?: continue
if (hit.distance > CoverageTracker.SEEN_RANGE_METERS) continue
```

`raycastPoint` (`:50-60`) uses the **legacy** `ARFrame.hitTest(_:types:)` with:

```kotlin
types = ARHitTestResultTypeExistingPlaneUsingGeometry
    or ARHitTestResultTypeEstimatedHorizontalPlane
    or ARHitTestResultTypeFeaturePoint
```

And plane detection is **horizontal-only**, in both the RealityKit and SceneKit paths:

- `iosApp/iosApp/GccArNative.swift:106` — `config.planeDetection = [.horizontal]`
- `composeApp/src/iosMain/.../ArEnvScan.kt:253` — `ARPlaneDetectionHorizontal`

So when the camera is aimed level at a facade across a 15–20m street:
- `EstimatedHorizontalPlane` — the ray never intersects the ground plane (it's aimed
  level/up), so no hit.
- `ExistingPlaneUsingGeometry` — no vertical plane was ever detected, so nothing to hit.
- `FeaturePoint` — sparse and unreliable at that range.

⇒ **No hit ⇒ no credit at ANY tier, including SEEN.** `SEEN_RANGE_METERS` is generous, but
it gates a hit that cannot occur. The scan genuinely stops at the street. This is not a
tuning bug; it is the geometry of the API being used.

**Owner decision (2026-07-09): keep ray-based semantics.** Do NOT fake coverage from a
sight frustum. The minimap must instead *honestly display* what the rays actually credited.

### F2. The minimap hides the very thing it should show

`ScanMinimapOverlay.kt`:
- The veil is drawn over the basemap; SEEN cells are explicitly **not** punched out of it
  (`:166` — "SEEN cells are NOT punched out of the veil"), so sighted ground stays dark.
- Session-credited cells (GRAZED/CLOSE/SEEN) are drawn at `:141`, the beam at `:82`.

Combined with F1, the worker sees a dark veil, no dot, no cone, and almost no punch-through
— because almost nothing is being credited.

## Design (NOT YET APPROVED — needs a session)

Sketch only, to be brainstormed:

1. **Legibility.** Lighten the veil; guarantee the user dot and the heading cone are drawn
   above every other minimap layer; ensure the basemap tile is visible beneath.
2. **Show the rays.** Render the actual ray-fan hits (or the credited cell set) each tick,
   so "what the phone can see" and "what got credited" are the same picture. If a ray
   didn't land, the worker should be able to tell.
3. **Follow the user.** Diagnose the "not moving with the user" report — likely the camera
   is keyed on the geo-ref anchor rather than the live fix, or the projection uses the
   wrong zoom bias (see `mapOverlayZoomBias(density) = log2(density × 2)`; a wrong bias
   renders geometry concentric-but-scaled and reads as "not tracking").
4. **Beam ⇒ shading.** The beam polygon already exists (`visibilityBeamPolygon`). Decide
   whether the beam *shades* SEEN ground (a visual-only affordance, coverage still
   ray-credited) — this reconciles "use the cone to shade in the areas captured" with
   "keep the ray technology".
5. **Reach, honestly.** Consider enabling `.vertical` plane detection so facades become
   hittable, and migrating `hitTest` → `raycastQuery(from:allowing:.estimatedPlane,
   alignment:.any)` (the modern API; `hitTest` is deprecated). This would genuinely extend
   reach across the street rather than faking it. **Cost:** more planes, more CPU/thermal
   on an already-hot path.

## Open questions

1. **The green bar.** Blake: "I still don't know what the green bar is during the
   environment scan that appears at the bottom of the screen rotating in view." It is
   AR-anchored (it rotates with the world), so it is not a Compose overlay. Candidates:
   the RealityKit ground-grid plane seen edge-on, a guidance element, or a coaching
   overlay. **Cannot be identified from code with confidence and cannot be reproduced in
   the simulator (no ARKit). Needs a screenshot.**
2. Does enabling vertical planes break the indoor gate? The gate flips INDOOR on a
   ceiling-classified plane; adding vertical planes changes what ARKit reports.
3. Thermal budget: `ArEnvScan.kt` already calls continuous Vision + plane detection +
   depth "the hottest path in the app", with a `thermalState >= 2` early-return.

## Related

- Spec 1: `2026-07-09-problem-area-map-legibility-design.md`
- Spec 3 (detection quality) will add the scan HUD (trash counter + weight estimate) and
  fix the detection reticle / AR tap-to-focus, both of which already exist and don't fire.
