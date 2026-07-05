# Map Visual Hierarchy — spots, block routes, and heatmap coexistence

**Date:** 2026-07-05
**Status:** Implemented (autonomous background session — assumptions listed below; revise freely)
**Trigger:** Blake's screenshot of the My Area map at ~z14: ~100 orange spot pins form an
opaque blob that buries the green block-route corridor, while the heat legend floats over
a zoom level where no heatmap renders.

## Problem

Three data layers compete on the main map with no hierarchy:

1. **Spot pins** are a 5–6 layer stack per point (soft halo up to 18dp, solid dot, white
   stroke, verification ring, bounty ring, glyph badge) drawn **topmost**. At z13–15 in a
   scanned area, overlapping stacks read as one orange mass; the route corridor beneath is
   invisible.
2. **Heatmap ↔ dots handoff is a hard cut** at z12.99/13.0. Dots pop in at ~full opacity.
   (The heat interpolations already contain fade stops at z13.1/13.8, but `maxZoom=12.99`
   clips them — dead code from an earlier crossfade design.)
3. **Heat renders above the route lines** (declaration order), so routes are washed out at
   exactly the zooms where Explorer users should spot route opportunities.
4. **The legend** (`PrecipitationLegend`) shows whenever the toggle is on, even at street
   zoom where no heat exists — it labels a layer that isn't on screen.

Data is currently limited; the design must hold when spot counts grow 10–100×.

## Design principle: one dominant layer per zoom band

| Zoom band | Dominant layer | Supporting layers |
|---|---|---|
| z2–12.5 (overview) | Heatmap (density) | Routes as thin green structure lines **above** heat |
| z12.5–14.2 (handoff) | Crossfade | Heat fades out as translucent dots fade in |
| z13–15.5 (neighborhood) | **Block routes / corridor** | Spots as small additive translucent dots, no halos |
| z15.5+ (street) | Individual spots | Halos, verification rings, glyph badges return; corridor band strengthens |

Key techniques (standard cartographic practice):

- **Density-additive dots:** at neighborhood zoom, dots are small (~3dp) and ≤0.75 opacity
  so overlaps *sum* visually into darker orange — density reads without an opaque blob,
  and it scales with data volume instead of degrading.
- **Detail budget by zoom:** halos, verification rings, and 30dp glyph badges are street-level
  detail (z15+/15.5+). Below that, every pin is just a dot; typed pins get a small-dot layer
  that crossfades into the glyph layer at z15.
- **Structure above texture:** heat is texture, route lines are structure. Heat moves below
  the route stack in declaration order so green loops stay readable at overview zooms.
- **Honest legend:** legend renders only while heat is actually visible (zoom < fade-out),
  via a new `onZoomChanged` callback from `GccMap`.

## Changes by file

### `MapStyles.kt`
- `SPOT_HEATMAP_MAX_ZOOM` / `SPOT_HEATMAP_FADE_OUT_ZOOM`: `12.99 → 14.2` (crossfade window).
- New `SPOT_DETAIL_MIN_ZOOM = 15.5` (halos, verification rings) and
  `SPOT_GLYPH_MIN_ZOOM = 15.0` (typed badges).
- Comment updated: handoff is now a deliberate crossfade, not a hard cut.

### `GccMap.ios.kt`
- Move spot scrim + 5 density-heat CircleLayers to **before** the route stack.
- Heat opacity final stops → 0 at fade zoom (real fade-out, not a clip).
- Dot layer: radius 3→9dp ramp, opacity fades in z13.2→14.5 capped 0.9, stroke thinned.
- New `spot-markers-typed-dots` CircleLayer (filter `has("itemType")`, maxZoom 15.2,
  fades out as glyphs fade in) so typed pins are dots at neighborhood zoom.
- Glyph SymbolLayer: minZoom 15.0.
- Halo layer: minZoom 15.5, alpha 0.16→0.10, smaller radii.
- Verification ring: minZoom 15.5 with fade-in.
- Corridor band opacity: flat 0.12 → interpolate z13 0.06 / z15 0.12 / z16.5 0.18.
- New `onZoomChanged` invoked from `snapshotFlow { camera.position.zoom }`.

### `GccMap.android.kt`
- Move scrim + both HeatmapLayers before the route stack; heat opacity/intensity final
  stops → 0 at fade zoom (window now extends to 14.2 with the existing 13/13.75/14.1 stops).
- Same dot restyle + halo/detail zoom gating as iOS (no glyph layer on Android; dots carry all pins).
- Same corridor band ramp + `onZoomChanged`.

### `GccMap.kt` (expect) / `GccMap.desktop.kt`
- New optional param `onZoomChanged: ((Double) -> Unit)? = null`. Desktop reports its
  static `zoom` param.

### `MapFullScreen.kt`
- Tracks `currentMapZoom` from `onZoomChanged`; legend shows only when
  `heatmapVisible && currentMapZoom < SPOT_HEATMAP_FADE_OUT_ZOOM`, with fade animation.

## Not in scope (future work, recorded deliberately)

- **True clustering** (cluster:true GeoJSON source options or client-side aggregation) —
  the additive-dot approach covers the current and mid-term data volume; revisit when a
  single viewport at z14 regularly exceeds ~500 pins.
- **Severity-driven pin color** — `CommunityReport` has no `severity` field;
  `WorldObservation.toScanMapPin()` drops it. Plumbing severity through is a data-model
  change that should ride an API round, not a styling PR.
- Web map parity (site/ map has its own known sprite gaps).

## Assumptions made autonomously

1. Keeping the heat ramp/colors and the 13.0 dots-min-zoom unchanged; only the *fade
   window* widens. If the crossfade band (12.5–14.2) feels muddy on device, narrow by
   lowering `SPOT_HEATMAP_FADE_OUT_ZOOM` to ~13.6 — single constant.
2. Halos/verification rings gated to z15.5 rather than deleted — they're good affordances
   when pins are sparse on screen.
3. Legend gating by zoom rather than removing the legend; the ●/○ toggle behavior is
   unchanged (still hides spots+heat together).
