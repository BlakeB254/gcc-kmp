# Precipitation-Style Heatmap — Design Spec

**Date:** 2026-06-29
**Status:** Awaiting review
**Scope:** Restyle the community-report density heatmap (web + iOS/Android + desktop) to resemble the Apple Weather precipitation map.

---

## 1. Goal

Make the GCC spot-report density heatmap look and feel like Apple Weather's
precipitation overlay — soft feathered blobs in a teal→blue→purple→red→white
intensity ramp on a muted basemap, with a floating intensity legend and a
layer-toggle control — in **production on both the web site and the mobile app**.

Reference: user screen recording of Apple Weather precipitation
(`~/Downloads/ScreenRecording_06-29-2026 11-38-19_1.mp4`).

## 2. Decisions (locked with user)

| Decision | Choice |
|---|---|
| Color semantics | **Full weather palette, transparent base.** Drop the green "clean-zone" base entirely; empty areas show the (muted) basemap. Density blooms teal→blue→purple→red→white. |
| Animation | **Timelapse (Phase 2).** Weather-Channel-style playback of report history. Rolling **14-day trailing window** swept across the **last 90 days** in an **~8s autoplay loop**. (The base restyle — Phase 1 — is still static and ships independently.) |
| Dots correlation | The close-zoom individual-spot **dots layer** is filtered by the **same time cursor** as the heatmap, so a report's dot and its heat bloom appear/fade together. |
| Heatmap-view UX | A **dedicated full-screen "Report Density" mode** (muted base + weather heatmap + correlated dots + legend + layer toggle + bottom timeline scrubber with play/pause + date readout), entered from a play/layers affordance. Keeps the normal map simple. |
| Basemap muting | **Translucent neutral scrim** drawn *under* the heatmap only when it is active. No tile-style swap. Reversible, identical across platforms, leaves other map views untouched. |
| Layer toggle | **Included.** Apple-style top-right stacked-layers button to show/hide the precipitation overlay. |
| UI elements | Floating intensity legend, soft feathered blobs, muted basemap, layer toggle. |

## 3. Background — current implementation

The "heatmap" is the **community-report (spot) density surface**. Three renderers:

1. **Web** — MapLibre GL via `react-map-gl/maplibre`.
   - `site/src/components/map/CombinedMap.tsx` — `combined-spot-heatmap` layer (native `heatmap` type) + `clean-zone-fill` green base + zoom-crossfaded dots.
   - `site/src/lib/design-tokens.ts` — `SPOT_HEATMAP_PAINT`, `SPOT_CLEAN_BASE_PAINT`, `SPOT_COLORS` (green doppler ramp).
2. **Native iOS/Android** — native Mapbox fed by GeoJSON.
   - `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt` — heat points + stacked circle-polygon zones + CPU density grid.
   - `composeApp/.../ui/map/GccMap.android.kt`, `GccMap.ios.kt` — native layer paint config.
   - `MapStyles.kt` — `SPOT_HEAT_*` colors, `CLEAN_BASE_COLOR`, heatmap paint constants.
3. **Desktop / canvas fallback** — hand-drawn Compose Canvas.
   - `MapRenderers.kt :: drawSpotMarkers` — stacked translucent circles (edge→low→medium→high→extreme→near-peak) + green base `drawRect`.

Current palette is an **inverted "green doppler"** (green = clean/good, red = worst),
semantically opposite to precipitation (more color = more intensity).

## 4. The weather palette

Single intensity ramp keyed to `heatmap-density` (web) / `heatWeight` stacking
(native + canvas). Defined once, mirrored in `design-tokens.ts` (web) and
`MapStyles.kt` (app) — the existing "keep in sync across platforms" convention.

| density stop | new color | hex | legend band |
|---|---|---|---|
| 0.00 | transparent | `rgba(0,0,0,0)` | — |
| 0.05 | teal | `#7FE5E0` | Light |
| 0.15 | cyan-blue | `#4FB0E8` | Light |
| 0.30 | blue | `#3B7DE0` | Light |
| 0.45 | indigo | `#6C5CE7` | Moderate |
| 0.62 | purple-magenta | `#9B4FD0` | Moderate |
| 0.75 | magenta | `#D24FAF` | Heavy |
| 0.85 | red-pink | `#E84B5A` | Heavy |
| 0.94 | yellow | `#F5D94F` | Extreme |
| 1.00 | white | `#FFFFFF` | Extreme |

The green `clean-zone` / `CLEAN_BASE` layers are **not rendered** (transparent base).

## 5. Soft feathered blobs

- **Web:** increase `heatmap-radius` ~25–30% across the existing zoom stops and
  ease color-stop spacing so edges fade out smoothly. Keep the existing
  zoom-based fade-out / dot crossfade behavior.
- **Native/canvas:** the renderers already stack translucent rings; widen the
  outermost ring multiplier for a softer falloff and apply the new colors.

## 6. Floating intensity legend

Replaces the current bottom-left "Map Legend" *for the heatmap view*.

- Top-left panel: vertical gradient bar of the ramp + labels Light / Moderate /
  Heavy / Extreme + a "Done" affordance to dismiss.
- **Web:** React component in `site/src/components/map/` rendered as a map overlay.
- **App:** Compose overlay composable layered over the map in the relevant screen
  (`MapFullScreen.kt` / feed map).

## 7. Muted basemap (scrim)

- A translucent neutral fill (e.g. `rgba(245,246,248,~0.55)` light / dark-mode
  variant) drawn **beneath** the heatmap color layers and **above** the basemap,
  only while the heatmap overlay is active.
- **Web:** an extra MapLibre `fill`/`background` layer inserted with `beforeId`
  set to the heatmap layer (mirrors how `clean-zone-fill` is inserted today).
- **Native:** equivalent fill layer beneath the heat layers.
- **Canvas:** a `drawRect` scrim before the heat circles (reuse the slot the green
  base `drawRect` occupies).

## 8. Layer toggle control

- Top-right stacked-layers button toggling heatmap overlay visibility.
- State lives in the map component; toggles visibility of the heatmap +
  scrim + legend together.
- **Web:** button overlay in `CombinedMap.tsx` (or shared `BaseMap` control slot).
- **App:** Compose icon button overlay; drives a `remember`ed visibility flag.

## 9. Files to change

**Web**
- `site/src/lib/design-tokens.ts` — new ramp in `SPOT_COLORS`/`SPOT_HEATMAP_PAINT`; widen radius; add scrim paint; retire green clean-base usage.
- `site/src/components/map/CombinedMap.tsx` — drop `clean-zone` layer, add scrim layer, new legend component, layer-toggle button, wire visibility state.
- New: `site/src/components/map/PrecipitationLegend.tsx` (or co-located).

**Native + shared (KMP)**
- `composeApp/.../ui/map/MapStyles.kt` — mirror ramp colors, scrim color, drop `CLEAN_BASE_COLOR` usage.
- `composeApp/.../ui/map/GccMap.android.kt`, `GccMap.ios.kt` — native heat layer paint → new ramp; insert scrim; remove green clean-zone layer.
- `composeApp/.../ui/map/GeoJsonLayers.kt` — adjust if green-base feature builders are retired.
- `composeApp/.../ui/map/MapRenderers.kt` — `drawSpotMarkers`: new colors, widen outer ring, replace green base `drawRect` with neutral scrim.
- New Compose: precipitation legend + layer-toggle overlays (screen-level, e.g. `MapFullScreen.kt`).

## 10. Out of scope

- Re-theming non-heatmap map views (routes, bounties, street detail).
- Tile-style replacement (using scrim instead).
- Server-side time aggregation — the timelapse filters client-side on existing `createdAt`.

## 12. Timelapse mode (Phase 2)

Weather-Channel-style playback of report history. Independent of Phase 1 (restyle);
builds on top of it.

**Data:** every `CommunityReport` has a required `createdAt: Instant`; the web
`SpotReport` carries `createdAt?: string`. Each map feature gains a numeric `ts`
property = `createdAt` epoch-millis. No new API/aggregation — filter client-side.

**Motion model — rolling trailing window:** for a play cursor `t`, a feature is
visible when `t - WINDOW ≤ ts ≤ t`, with `WINDOW = 14 days`. Blooms fade out as
they age past the window. Implemented as a MapLibre **filter expression** on both
the heatmap and dots layers keyed to `cursorMs`:
`['all', ['<=', ['get','ts'], cursorMs], ['>', ['get','ts'], cursorMs - WINDOW_MS]]`.
Native MapLibre-Compose uses the same filter; the desktop canvas filters the spot
list per frame.

**Timeline:** span = **last 90 days** (`now - 90d` → `now`). Playback = **~8s
loop**, **autoplay** on entering the mode. A `requestAnimationFrame` (web) /
Compose animation (app) advances `cursorMs` linearly across the span and wraps.

**Dots correlation:** the dots layer uses the identical filter, so individual spot
dots appear/disappear in lockstep with the heatmap blooms.

**Dedicated full-screen view ("Report Density"):**
- Muted scrim + weather heatmap + correlated dots + intensity legend (Phase 1 parts).
- **Bottom timeline scrubber**: a draggable track showing the 90-day span, a moving
  thumb at `cursorMs`, a **play/pause** button, and a **date readout** of the
  current cursor (e.g. "Apr 12").
- Dragging the thumb scrubs (pauses autoplay); play resumes the loop.
- Entered from a play/layers affordance on the normal map (web `/bounties`; app map
  screen). Exiting returns to the simple map.
- **Web:** new `PrecipitationTimelapse.tsx` wrapping the map + scrubber; reuses
  `CombinedMap` layers with the cursor filter applied.
- **App:** new full-screen composable (e.g. `ReportDensityTimelapseScreen.kt`)
  reusing the heat layers with the filter + a Compose scrubber.

**Constants (shared, mirror in both platforms):** `WINDOW_DAYS = 14`,
`SPAN_DAYS = 90`, `LOOP_MS = 8000`.

## 11. Risks / verification

- **Production on main:** changes land on a feature branch; promotion to prod is a
  separate, user-controlled step.
- **Cross-platform parity:** the ramp is duplicated in TS + Kotlin (no shared
  codegen); spec table is the single source of truth — both must match it.
- **Legibility:** transparent base means low-density areas rely on the muted
  scrim for "calm" reading; verify contrast in both light and dark map modes.
- **Verify:** web via local dev + screenshot against the reference frames; app via
  simulator screenshot at overview zoom (z8–z13) where the heatmap dominates.
