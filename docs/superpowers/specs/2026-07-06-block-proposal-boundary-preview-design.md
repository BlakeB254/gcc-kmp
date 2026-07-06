# Block Proposal Boundary Preview — Design

**Date:** 2026-07-06
**Status:** Approved for implementation (autonomous session — Blake asked for
"an image of the proposed block with its boundaries in the display card" in
the iOS admin > Block Proposals tab; design decisions below are recorded
assumptions, flag anything off in PR review.)

## Problem

`AdminBlockProposalsTab` cards show only text (code hint, corners, street,
batch). The `BlockProposal` DTO already carries `boundary: GeoJSONPolygon`
and `center: GeoJSONPoint`, but nothing renders them — an admin cannot see
*where* the proposed block is or whether its boundary makes sense without
cross-referencing the web admin. Approve/Reject is effectively blind on
mobile.

## Approach (chosen)

**Per-card non-interactive mini `GccMap`** framed to the proposal boundary,
between the card header and the metadata rows.

- Precedent: `BountyListScreen` and `WalletScreen` already embed per-card
  `GccMap` minimaps inside scrollable lists (gestures off, buildings/grid
  off) — proven pattern, LazyColumn only composes visible cards.
- The boundary outer ring is passed as `routeCoordinates`, so it renders
  with the app's standard block-boundary styling (same as real blocks on
  the map — the honest preview of what approval creates).
- `innerBoundaryFallback = false` suppresses the synthetic 8m-inset ring
  (editor-mode param); proposals only carry an outer ring + an exclusion
  *count*, no island geometry.
- `instantCamera = true`: the camera is state-driven; the card must appear
  already framed, no easing from world view.

### Alternatives rejected

1. **Canvas-only polygon sketch** — cheap, but no basemap. Admins judge
   proposals against streets/parks ("crosses a park boundary" is the
   canonical rejection reason); a floating polygon on a flat color can't
   support that decision.
2. **One shared overview map + selected proposal** (AdminRoutesTab pattern)
   — doesn't satisfy "image in the display card"; adds selection state.
3. **Server-rendered static images** — new server surface, caching, network
   failure modes; overkill when the geometry is already in the DTO.

## Components

### 1. `boundaryFitZoom(...)` — shared fit-zoom helper (`MapStyles.kt`)

The per-axis fit formula currently inlined in
`AdminBoundaryEditorScreen.kt` (LaunchedEffect at ~L535): north-south span
fits viewport height, east-west span fits width, tighter axis wins;
1.25 padding factor; 30m span floor; result minus `mapOverlayZoomBias`
(physical-px 256-tile zoom → MapLibre point-space 512-tile zoom).

```kotlin
fun boundaryFitZoom(
    latSpanDeg: Double, lngSpanDeg: Double, centerLat: Double,
    widthPx: Float, heightPx: Float, zoomBias: Double,
): Double
```

Unclamped; call sites apply their own `coerceIn`. The boundary editor is
refactored to call it (one source of truth); behavior there is unchanged.

### 2. `BlockProposalCard` preview (`AdminBlockProposalsTab.kt`)

- 150dp-tall `Box` clipped to 8dp rounded corners, full card width, placed
  after the header row.
- `BoxWithConstraints` supplies the box size in px (with `LocalDensity`)
  for the fit computation.
- Ring conversion: `Point` typealias is **`[lng, lat]`** GeoJSON order →
  `Coordinates(p[0], p[1])` (positional-arg transposition is a recurring
  bug class in this repo; the fit-zoom test pins spans, and the ring is
  bbox-checked).
- Camera: center = boundary bbox midpoint; zoom = `boundaryFitZoom(...)`
  clamped to `13.0..17.5`. Degenerate ring (< 3 points): omit the map box
  entirely (text card as today).
- Map params: `enableGestures=false, showBuildings=false,
  showGridLines=false, instantCamera=true, innerBoundaryFallback=false`.
- Neighborhood/side overlay chips: **not added** (already text on the card).

## Data flow

`GET /api/admin/block-proposals?status=pending` (existing) →
`AdminPanelViewModel.blockProposals` → `BlockProposalCard` → geometry is
purely client-rendered. No API or server changes.

## Error handling

- Missing/degenerate boundary → no map box (graceful text-only card).
- Everything else is existing GccMap behavior (tile failures degrade to
  blank basemap; the boundary line still renders since it's a layer, not a
  tile).

## Testing

- `BoundaryFitZoomTest` in `composeApp` commonTest (shared's own commonTest
  is broken on all targets — WorldObservationPinTest precedent): frozen
  expected zoom for a known bbox/viewport, larger-span ⇒ smaller zoom,
  span floor at 30m, per-axis tighter-wins.
- Compile verification: `:composeApp:desktopTest`, iOS sim framework link,
  `xcodebuild` sim build (standard repo drill; CI cannot see iosMain).
- Live sim check of the admin tab is not possible this session (demo
  account is not in ADMIN_EMAILS; pending-proposal fixtures unknown) —
  flagged in the PR for Blake's next admin-account sim pass.
