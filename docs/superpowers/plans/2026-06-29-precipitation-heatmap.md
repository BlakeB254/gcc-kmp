# Precipitation-Style Heatmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the GCC community-report density heatmap (web + native iOS/Android + desktop canvas) to look like the Apple Weather precipitation overlay.

**Architecture:** The heatmap already exists in three renderers that all read from per-platform style tokens. We swap the green "doppler" ramp for a weather ramp (teal→blue→purple→red→white), drop the green clean-base for a transparent base under a neutral scrim, widen blob radius for soft feathering, and add a floating intensity legend + layer-toggle on each platform. No new architecture — only token values, paint config, two new overlay components, and removal of the green base layer.

**Tech Stack:** Web — Next.js + `react-map-gl/maplibre`, Playwright e2e (no unit runner). Native/desktop — Kotlin Multiplatform + Compose + `org.maplibre.compose` `HeatmapLayer` + Compose Canvas.

## Global Constraints

- **Single source of truth for the ramp** is the spec table in `docs/superpowers/specs/2026-06-29-precipitation-heatmap-design.md §4`. Web (`design-tokens.ts`) and app (`MapStyles.kt`) values MUST match it — there is no shared codegen.
- **Weather ramp (density → color):** `0.00` transparent · `0.05` `#7FE5E0` · `0.15` `#4FB0E8` · `0.30` `#3B7DE0` · `0.45` `#6C5CE7` · `0.62` `#9B4FD0` · `0.75` `#D24FAF` · `0.85` `#E84B5A` · `0.94` `#F5D94F` · `1.00` `#FFFFFF`.
- **Scrim color:** neutral `#F5F6F8` at ~`0.55` alpha, drawn under the heatmap, only when the overlay is active.
- **No animation** — static only.
- **No unit-test harness exists** for this UI. Each task's test cycle = (a) the relevant compiler/linter passes, plus (b) a visual/DOM verification (Playwright e2e for web DOM controls; screenshot vs. the reference frames in `~/.claude/jobs/.../frames/` for the heatmap visual; simulator screenshot for the app). Do not introduce a new unit-test framework.
- **No production deploy** in this plan — work stays on the feature branch; promotion is user-controlled.
- Commit after every task.

---

### Task 1: Web — weather palette, transparent base, feathered radius

**Files:**
- Modify: `site/src/lib/design-tokens.ts` (`SPOT_COLORS` ~62-74, `SPOT_HEATMAP_PAINT` ~124-174; add `SPOT_SCRIM_PAINT`)

**Interfaces:**
- Produces: `SPOT_SCRIM_PAINT` (MapLibre `fill` paint object) consumed by Task 2; `SPOT_HEATMAP_PAINT` (restyled) already consumed by `CombinedMap.tsx`.

- [ ] **Step 1: Replace the `SPOT_COLORS` heatmap entries with the weather ramp.** In `site/src/lib/design-tokens.ts`, change the heatmap color block inside `SPOT_COLORS` to:

```ts
  // Precipitation ramp — teal(light) → blue → purple(moderate) → red(heavy) → white(extreme)
  HEATMAP_LIGHT_TEAL: '#7FE5E0',
  HEATMAP_LIGHT_BLUE: '#4FB0E8',
  HEATMAP_BLUE: '#3B7DE0',
  HEATMAP_INDIGO: '#6C5CE7',
  HEATMAP_PURPLE: '#9B4FD0',
  HEATMAP_MAGENTA: '#D24FAF',
  HEATMAP_REDPINK: '#E84B5A',
  HEATMAP_YELLOW: '#F5D94F',
  HEATMAP_PEAK: '#FFFFFF',
```

(Remove the old `HEATMAP_EDGE/LOW/CLEAN_ZONE/MED/HIGH/EXTREME/PEAK` keys.)

- [ ] **Step 2: Rewrite `SPOT_HEATMAP_PAINT`'s `heatmap-color` ramp and widen `heatmap-radius`.** Replace the `'heatmap-color'` array with:

```ts
  'heatmap-color': [
    'interpolate', ['linear'], ['heatmap-density'],
    0,    'rgba(0,0,0,0)',
    0.05, 'rgba(127,229,224,0.55)',
    0.15, SPOT_COLORS.HEATMAP_LIGHT_BLUE,
    0.30, SPOT_COLORS.HEATMAP_BLUE,
    0.45, SPOT_COLORS.HEATMAP_INDIGO,
    0.62, SPOT_COLORS.HEATMAP_PURPLE,
    0.75, SPOT_COLORS.HEATMAP_MAGENTA,
    0.85, SPOT_COLORS.HEATMAP_REDPINK,
    0.94, SPOT_COLORS.HEATMAP_YELLOW,
    1.0,  SPOT_COLORS.HEATMAP_PEAK,
  ],
```

And replace `heatmap-radius` stops with these (~28% wider for feathering):

```ts
  'heatmap-radius': [
    'interpolate', ['linear'], ['zoom'],
    8, 23,
    10, 36,
    12, 54,
    13, 67,
    13.75, 44,
    14.25, 23,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0,
    SPOT_ZOOM.HEATMAP_MAX, 0,
  ],
```

Leave `heatmap-weight`, `heatmap-intensity`, `heatmap-opacity` unchanged.

- [ ] **Step 3: Add `SPOT_SCRIM_PAINT` below `SPOT_HEATMAP_PAINT`.**

```ts
// Neutral scrim drawn beneath the heatmap so weather colors pop (Apple precipitation feel).
export const SPOT_SCRIM_PAINT: any = {
  'fill-color': '#F5F6F8',
  'fill-opacity': [
    'interpolate', ['linear'], ['zoom'],
    8, 0.55,
    13, 0.5,
    14, 0.2,
    SPOT_ZOOM.HEATMAP_FADE_OUT, 0,
  ],
}
```

- [ ] **Step 4: Typecheck + lint.**

Run: `cd site && npx tsc --noEmit && npm run lint`
Expected: no type errors; lint passes (pre-existing warnings unrelated to these files are acceptable).

- [ ] **Step 5: Commit.**

```bash
git add site/src/lib/design-tokens.ts
git commit -m "feat(map-web): precipitation weather palette + scrim paint + feathered radius"
```

---

### Task 2: Web — drop green clean-base, insert neutral scrim

**Files:**
- Modify: `site/src/components/map/CombinedMap.tsx` (clean-zone source/layer ~603-626; imports ~15-18)

**Interfaces:**
- Consumes: `SPOT_SCRIM_PAINT` from Task 1.
- Produces: the `clean-zone-fill` layer id is gone; a `spot-scrim-fill` layer id now sits beneath `combined-spot-heatmap`.

- [ ] **Step 1: Swap the import.** In the `@/lib/design-tokens` import block, remove `SPOT_CLEAN_BASE_PAINT` and add `SPOT_SCRIM_PAINT`.

- [ ] **Step 2: Replace the green clean-base `<Source>`/`<Layer>` block** (the `id="clean-zone-base"` source containing `clean-zone-fill`, currently ~603-626) with the scrim layer over the same world polygon:

```tsx
        {/* Neutral scrim — mutes the basemap beneath the precipitation heatmap */}
        {spots.length > 0 && (
          <Source
            id="spot-scrim"
            type="geojson"
            data={{
              type: 'FeatureCollection',
              features: [{
                type: 'Feature',
                properties: {},
                geometry: {
                  type: 'Polygon',
                  coordinates: [[[-180, -85], [180, -85], [180, 85], [-180, 85], [-180, -85]]],
                },
              }],
            }}
          >
            <Layer
              id="spot-scrim-fill"
              type="fill"
              beforeId="combined-route-case-layer"
              paint={SPOT_SCRIM_PAINT}
            />
          </Source>
        )}
```

- [ ] **Step 3: Typecheck + build.**

Run: `cd site && npx tsc --noEmit`
Expected: no type errors; no remaining references to `SPOT_CLEAN_BASE_PAINT` (grep `git grep -n SPOT_CLEAN_BASE_PAINT site/src` returns nothing).

- [ ] **Step 4: Visual check.** Start dev (`cd site && npm run dev` on port 7350), open `http://localhost:7350/bounties`, zoom to overview (z9–z12). Confirm: no green wash; map is muted gray; report clusters bloom teal→blue→purple→red. Compare against reference frames `f_002.jpg`, `f_006.jpg`.

- [ ] **Step 5: Commit.**

```bash
git add site/src/components/map/CombinedMap.tsx
git commit -m "feat(map-web): replace green clean-base with neutral scrim"
```

---

### Task 3: Web — floating precipitation legend

**Files:**
- Create: `site/src/components/map/PrecipitationLegend.tsx`
- Modify: `site/src/components/map/CombinedMap.tsx` (legend wiring ~447-469, ~507)

**Interfaces:**
- Produces: `PrecipitationLegend` (default props: `onDone?: () => void`). Renders a panel with `data-testid="precip-legend"`.

- [ ] **Step 1: Create the component.**

```tsx
'use client'

// Vertical intensity scale matching the precipitation weather ramp (bottom = Light, top = Extreme).
const RAMP_BOTTOM_TO_TOP = [
  '#7FE5E0', '#4FB0E8', '#3B7DE0', '#6C5CE7',
  '#9B4FD0', '#D24FAF', '#E84B5A', '#F5D94F', '#FFFFFF',
]

export function PrecipitationLegend({ onDone }: { onDone?: () => void }) {
  const gradient = `linear-gradient(to top, ${RAMP_BOTTOM_TO_TOP.join(', ')})`
  return (
    <div
      data-testid="precip-legend"
      className="absolute top-3 left-3 bg-white/95 backdrop-blur-sm rounded-xl p-3 shadow-md z-10 w-36"
    >
      {onDone && (
        <button
          data-testid="precip-legend-done"
          onClick={onDone}
          className="text-sm font-semibold text-blue-600 mb-2"
        >
          Done
        </button>
      )}
      <div className="text-xs font-semibold text-neutral-700 mb-2">Report Density</div>
      <div className="flex gap-2">
        <div className="w-3 rounded-full" style={{ background: gradient, height: 96 }} />
        <div className="flex flex-col justify-between text-[11px] text-neutral-600 py-0.5">
          <span>Extreme</span>
          <span>Heavy</span>
          <span>Moderate</span>
          <span>Light</span>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Wire it in `CombinedMap.tsx`.** Import it (`import { PrecipitationLegend } from './PrecipitationLegend'`). When `spots.length > 0`, pass it as the map legend instead of the existing `legendNode`. Replace the `legend={legendNode}` prop value passed to `<BaseMap>` with:

```tsx
        legend={spots.length > 0 ? <PrecipitationLegend /> : legendNode}
```

(Keep `legendNode` for the routes/bounties-only case.)

- [ ] **Step 3: Write the e2e check.** Create `site/e2e/tests/precipitation-heatmap.spec.ts` following the `visual-regression.spec.ts` pattern:

```ts
import { test, expect } from '@playwright/test'

test('precipitation legend renders on the bounties map', async ({ page }) => {
  await page.goto('/bounties')
  const legend = page.getByTestId('precip-legend')
  await expect(legend).toBeVisible()
  await expect(legend).toContainText('Light')
  await expect(legend).toContainText('Extreme')
})
```

- [ ] **Step 4: Run the e2e test.**

Run: `cd site && npm run test:e2e -- precipitation-heatmap`
Expected: PASS (the legend is visible and contains Light/Extreme). If the map needs seed spots, ensure the e2e fixture/seed provides at least one spot (see `site/e2e/fixtures`).

- [ ] **Step 5: Commit.**

```bash
git add site/src/components/map/PrecipitationLegend.tsx site/src/components/map/CombinedMap.tsx site/e2e/tests/precipitation-heatmap.spec.ts
git commit -m "feat(map-web): floating precipitation intensity legend + e2e"
```

---

### Task 4: Web — layer-toggle control

**Files:**
- Modify: `site/src/components/map/CombinedMap.tsx` (state, heatmap/scrim layer visibility, legend gating)

**Interfaces:**
- Consumes: `PrecipitationLegend` (Task 3), `SPOT_SCRIM_PAINT` / `SPOT_HEATMAP_PAINT`.
- Produces: a `heatmapVisible` state controlling `spot-scrim-fill`, `combined-spot-heatmap`, and the legend together; a button with `data-testid="precip-toggle"`.

- [ ] **Step 1: Add state.** Near the other `useState` calls in `CombinedMap`, add:

```tsx
  const [heatmapVisible, setHeatmapVisible] = useState(true)
```

- [ ] **Step 2: Gate the layers.** Add `layout={{ visibility: heatmapVisible ? 'visible' : 'none' }}` to both the `spot-scrim-fill` `<Layer>` (Task 2) and the `combined-spot-heatmap` `<Layer>`.

- [ ] **Step 3: Add the toggle button** as a sibling overlay. Inside the returned JSX, after `<BaseMap ...>`'s children or as part of the legend area, render (only when `spots.length > 0`):

```tsx
        <button
          data-testid="precip-toggle"
          onClick={() => setHeatmapVisible((v) => !v)}
          aria-pressed={heatmapVisible}
          className="absolute top-3 right-3 z-10 bg-white/95 backdrop-blur-sm rounded-xl p-2 shadow-md"
          title="Toggle report density"
        >
          {/* stacked-layers glyph */}
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polygon points="12 2 22 8.5 12 15 2 8.5 12 2" />
            <polyline points="2 15.5 12 22 22 15.5" />
          </svg>
        </button>
```

- [ ] **Step 4: Gate the legend on the toggle.** Update the legend prop:

```tsx
        legend={spots.length > 0 && heatmapVisible ? <PrecipitationLegend onDone={() => setHeatmapVisible(false)} /> : legendNode}
```

- [ ] **Step 5: Extend the e2e test.** Append to `precipitation-heatmap.spec.ts`:

```ts
test('layer toggle hides and shows the precipitation overlay', async ({ page }) => {
  await page.goto('/bounties')
  await expect(page.getByTestId('precip-legend')).toBeVisible()
  await page.getByTestId('precip-toggle').click()
  await expect(page.getByTestId('precip-legend')).toHaveCount(0)
  await page.getByTestId('precip-toggle').click()
  await expect(page.getByTestId('precip-legend')).toBeVisible()
})
```

- [ ] **Step 6: Run e2e + typecheck.**

Run: `cd site && npx tsc --noEmit && npm run test:e2e -- precipitation-heatmap`
Expected: both tests PASS.

- [ ] **Step 7: Commit.**

```bash
git add site/src/components/map/CombinedMap.tsx site/e2e/tests/precipitation-heatmap.spec.ts
git commit -m "feat(map-web): precipitation layer-toggle control"
```

---

### Task 5: Native/desktop — weather palette + scrim constants in MapStyles

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt` (`SPOT_HEAT_*` ~137-146; add `SPOT_SCRIM_COLOR`)

**Interfaces:**
- Produces: restyled `SPOT_HEAT_EDGE/LOW/MEDIUM/HIGH/EXTREME/CRITICAL/NEAR_PEAK/PEAK`, new `SPOT_SCRIM_COLOR`. Consumed by `GccMap.android.kt`, `GccMap.ios.kt` (HeatmapLayer color stops) and `MapRenderers.kt` (canvas) — all already reference these constants.

- [ ] **Step 1: Replace the `SPOT_HEAT_*` color values** (map the 10-stop ramp onto the existing 7 bins) and add the scrim color:

```kotlin
    val SPOT_MARKER_COLOR = Color(0xFFF97316)    // orange (unchanged — dots)
    val SPOT_HEAT_EDGE = Color(0xFF7FE5E0)       // teal — Light
    val SPOT_HEAT_LOW = Color(0xFF4FB0E8)        // cyan-blue — Light
    val SPOT_HEAT_MEDIUM = Color(0xFF6C5CE7)     // indigo — Moderate
    val SPOT_HEAT_HIGH = Color(0xFF9B4FD0)       // purple-magenta — Moderate
    val SPOT_HEAT_EXTREME = Color(0xFFE84B5A)    // red-pink — Heavy
    val SPOT_HEAT_CRITICAL = Color(0xFFD24FAF)   // magenta — Heavy
    val SPOT_HEAT_NEAR_PEAK = Color(0xFFF5D94F)  // yellow — Extreme
    val SPOT_HEAT_PEAK = Color.White             // Extreme peak
    val CLEAN_BASE_COLOR = Color(0xFF128F3A)     // retained but no longer used for heat base
    val SPOT_SCRIM_COLOR = Color(0xFFF5F6F8)     // neutral scrim beneath heatmap
```

- [ ] **Step 2: Compile commonMain.**

Run: `./gradlew :composeApp:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt
git commit -m "feat(map-app): weather palette + scrim color constants"
```

---

### Task 6: Native/desktop — drop green base, apply scrim

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/ui/map/GccMap.android.kt` (clean-zone base FillLayer; heat layers already auto-restyled via Task 5)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt` (same)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapRenderers.kt` (`drawSpotMarkers` ~437-477)

**Interfaces:**
- Consumes: `SPOT_SCRIM_COLOR` + restyled `SPOT_HEAT_*` from Task 5.

- [ ] **Step 1: Read the three files** to locate (a) any clean-zone/`CLEAN_BASE` green FillLayer fed by `buildSpotCleanZoneBaseGeoJson`, and (b) the canvas base `drawRect`. Run: `git grep -n "CleanZone\|clean-zone\|CLEAN_BASE\|buildSpotCleanZoneBase" composeApp/src`.

- [ ] **Step 2: Native — repaint the clean-zone base to the scrim.** In `GccMap.android.kt` and `GccMap.ios.kt`, wherever the planet-spanning clean-zone `FillLayer` is configured with `CLEAN_BASE_COLOR` (green), change its color to `MapStyles.SPOT_SCRIM_COLOR` and set its opacity ramp to fade out by `SPOT_HEATMAP_FADE_OUT_ZOOM` (mirror the existing green-base opacity stops; cap max opacity at `0.55`). If the clean-zone layer is already removed/disabled in a file (see the note near line 296 in `GccMap.android.kt`), instead add a scrim `FillLayer` with `SPOT_SCRIM_COLOR` directly beneath `LAYER_SPOT_HEATMAP`.

- [ ] **Step 3: Canvas — swap the green base for the scrim and widen the outer ring.** In `MapRenderers.kt :: drawSpotMarkers`:
  - Change `drawRect(color = MapStyles.CLEAN_BASE_COLOR.copy(alpha = baseAlpha))` to `drawRect(color = MapStyles.SPOT_SCRIM_COLOR.copy(alpha = (baseAlpha * 1.8f).coerceAtMost(0.55f)))`.
  - Change the outermost heat ring multiplier from `radius * 1.65f` to `radius * 2.0f` for a softer falloff (the `SPOT_HEAT_EDGE` `drawCircle` line).

- [ ] **Step 4: Compile.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Visual check (simulator).** Build & run the app to a simulator/emulator, open the map screen at overview zoom. Confirm muted base + weather-colored blooms, no green field. (iOS: use the xcodebuild tooling; Android: emulator.)

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/ui/map/GccMap.android.kt composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapRenderers.kt
git commit -m "feat(map-app): drop green heat base, apply neutral scrim + softer falloff"
```

---

### Task 7: Native — Compose precipitation legend + layer toggle

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/PrecipitationLegend.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` (overlay the legend + toggle; gate heat layer visibility)

**Interfaces:**
- Consumes: `MapStyles.SPOT_HEAT_*`.
- Produces: `PrecipitationLegend(modifier)` composable; `MapFullScreen` holds a `heatmapVisible` state.

- [ ] **Step 1: Create the legend composable.**

```kotlin
package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Floating Apple-Weather-style intensity legend (bottom = Light, top = Extreme). */
@Composable
fun PrecipitationLegend(modifier: Modifier = Modifier) {
    // Top-to-bottom = Extreme → Light, matching the vertical bar.
    val rampTopToBottom = listOf(
        MapStyles.SPOT_HEAT_PEAK, MapStyles.SPOT_HEAT_NEAR_PEAK, MapStyles.SPOT_HEAT_CRITICAL,
        MapStyles.SPOT_HEAT_EXTREME, MapStyles.SPOT_HEAT_HIGH, MapStyles.SPOT_HEAT_MEDIUM,
        MapStyles.SPOT_HEAT_LOW, MapStyles.SPOT_HEAT_EDGE,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.95f))
            .padding(10.dp),
    ) {
        Box(
            Modifier.width(12.dp).height(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(rampTopToBottom)),
        )
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.height(96.dp)) {
            listOf("Extreme", "Heavy", "Moderate", "Light").forEach {
                Text(it, fontSize = 11.sp, color = Color(0xFF525252))
            }
        }
    }
}
```

- [ ] **Step 2: Overlay legend + toggle in `MapFullScreen.kt`.** Read the file first to find the map `Box`. Add state and overlays:

```kotlin
    var heatmapVisible by remember { mutableStateOf(true) }
```

Wrap the map in a `Box`; when `heatmapVisible`, overlay `PrecipitationLegend(Modifier.align(Alignment.TopStart).padding(12.dp))`, and always overlay a toggle `IconButton`/`FilledTonalIconButton` at `Alignment.TopEnd` that flips `heatmapVisible`. Pass `heatmapVisible` down so the `HeatmapLayer`s (and scrim layer) are only composed when true (wrap their call sites in `if (heatmapVisible) { ... }`, or set their layer visibility).

- [ ] **Step 3: Compile.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Visual check (simulator).** Open the map screen; confirm the legend shows Light→Extreme bottom-to-top and the top-right toggle hides/shows the overlay.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/PrecipitationLegend.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat(map-app): precipitation legend + layer-toggle overlay"
```

---

---

## Phase 2 — Timelapse (Weather-Channel-style playback)

> Phase 2 builds on Phase 1. Spec §12. Rolling **14-day** trailing window swept across the **last 90 days** in an **~8s autoplay loop**; the dots layer is filtered by the **same cursor**; lives in a **dedicated full-screen "Report Density" mode**. Shared constants (mirror both platforms): `WINDOW_DAYS = 14`, `SPAN_DAYS = 90`, `LOOP_MS = 8000`.

### Task 8: Web — `ts` on features + timelapse constants

**Files:**
- Modify: `site/src/lib/design-tokens.ts` (add timelapse constants)
- Modify: `site/src/components/map/CombinedMap.tsx` (`spotGeoJson` properties ~176-197)

**Interfaces:**
- Produces: `TIMELAPSE = { WINDOW_DAYS: 14, SPAN_DAYS: 90, LOOP_MS: 8000 }`; each spot feature gains a numeric `ts` property (epoch ms).

- [ ] **Step 1: Add constants** to `design-tokens.ts`:

```ts
export const TIMELAPSE = {
  WINDOW_DAYS: 14,
  SPAN_DAYS: 90,
  LOOP_MS: 8000,
} as const
```

- [ ] **Step 2: Add `ts` to `spotGeoJson` feature properties** in `CombinedMap.tsx`. In the `spots.map(...)` `properties` object, add:

```tsx
          ts: spot.createdAt ? new Date(spot.createdAt).getTime() : 0,
```

- [ ] **Step 3: Typecheck.** Run: `cd site && npx tsc --noEmit` — Expected: no errors.

- [ ] **Step 4: Commit.**

```bash
git add site/src/lib/design-tokens.ts site/src/components/map/CombinedMap.tsx
git commit -m "feat(map-web): add ts feature property + timelapse constants"
```

---

### Task 9: Web — cursor filter engine on heatmap + dots

**Files:**
- Modify: `site/src/components/map/CombinedMap.tsx` (accept `cursorMs?` prop; apply `filter` to `combined-spot-heatmap` and `combined-spot-center` layers)

**Interfaces:**
- Consumes: `TIMELAPSE`, the `ts` property (Task 8).
- Produces: `CombinedMap` accepts optional `cursorMs?: number`. When set, both the heatmap and dots layers filter to the trailing window; when undefined, no filter (Phase-1 behavior).

- [ ] **Step 1: Add the prop** to `CombinedMapProps`: `cursorMs?: number`, and destructure it in the component signature.

- [ ] **Step 2: Build the filter** (memoized) inside the component:

```tsx
  const timeFilter = useMemo(() => {
    if (cursorMs == null) return undefined
    const windowMs = TIMELAPSE.WINDOW_DAYS * 86_400_000
    return ['all',
      ['<=', ['get', 'ts'], cursorMs],
      ['>', ['get', 'ts'], cursorMs - windowMs],
    ] as any
  }, [cursorMs])
```

- [ ] **Step 3: Apply it** by adding `filter={timeFilter}` to the `combined-spot-heatmap` `<Layer>` and the `combined-spot-center` `<Layer>` (the dots). Import `TIMELAPSE` from design-tokens.

- [ ] **Step 4: Typecheck.** Run: `cd site && npx tsc --noEmit` — Expected: no errors.

- [ ] **Step 5: Manual check.** Temporarily render `<CombinedMap cursorMs={Date.now() - 30*86400000} .../>` on `/bounties`; confirm only reports within the 14-day trailing window of that cursor show. Revert the temporary prop.

- [ ] **Step 6: Commit.**

```bash
git add site/src/components/map/CombinedMap.tsx
git commit -m "feat(map-web): time-cursor filter on heatmap + dots layers"
```

---

### Task 10: Web — full-screen Report Density timelapse view

**Files:**
- Create: `site/src/components/map/PrecipitationTimelapse.tsx`
- Modify: `site/src/app/(app)/bounties/page.tsx` (entry affordance to open the timelapse)

**Interfaces:**
- Consumes: `CombinedMap` (`cursorMs` prop, Task 9), `TIMELAPSE`.
- Produces: `PrecipitationTimelapse({ bounties, spots, routes })` full-screen overlay with scrubber + play/pause + date readout; `data-testid` hooks `precip-timelapse`, `precip-play`, `precip-scrubber`, `precip-date`.

- [ ] **Step 1: Create the component.**

```tsx
'use client'

import { useState, useRef, useEffect, useCallback } from 'react'
import { CombinedMap, type CombinedMapProps } from './CombinedMap'
import { TIMELAPSE } from '@/lib/design-tokens'

const DAY = 86_400_000

export function PrecipitationTimelapse(props: Omit<CombinedMapProps, 'cursorMs'> & { onClose?: () => void }) {
  const spanMs = TIMELAPSE.SPAN_DAYS * DAY
  const end = Date.now()
  const start = end - spanMs
  const [cursorMs, setCursorMs] = useState(start)
  const [playing, setPlaying] = useState(true)
  const raf = useRef<number | null>(null)
  const last = useRef<number>(0)

  const tick = useCallback((now: number) => {
    if (last.current === 0) last.current = now
    const dt = now - last.current
    last.current = now
    setCursorMs((c) => {
      const next = c + (dt / TIMELAPSE.LOOP_MS) * spanMs
      return next >= end ? start : next
    })
    raf.current = requestAnimationFrame(tick)
  }, [spanMs, end, start])

  useEffect(() => {
    if (playing) { last.current = 0; raf.current = requestAnimationFrame(tick) }
    return () => { if (raf.current) cancelAnimationFrame(raf.current) }
  }, [playing, tick])

  const dateLabel = new Date(cursorMs).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  const pct = ((cursorMs - start) / spanMs) * 100

  return (
    <div data-testid="precip-timelapse" className="fixed inset-0 z-50 bg-black">
      <CombinedMap {...props} cursorMs={cursorMs} height="100vh" showLegend={false} />
      {props.onClose && (
        <button onClick={props.onClose} className="absolute top-3 right-3 z-[60] bg-white/95 rounded-xl px-3 py-1.5 text-sm font-semibold shadow-md">Done</button>
      )}
      {/* Bottom scrubber */}
      <div className="absolute bottom-6 left-1/2 -translate-x-1/2 z-[60] w-[min(680px,92vw)] bg-white/95 backdrop-blur-sm rounded-2xl p-3 shadow-lg flex items-center gap-3">
        <button data-testid="precip-play" onClick={() => setPlaying((p) => !p)} aria-pressed={playing} className="w-9 h-9 flex items-center justify-center rounded-full bg-blue-600 text-white">
          {playing ? '❚❚' : '▶'}
        </button>
        <input
          data-testid="precip-scrubber"
          type="range" min={start} max={end} step={DAY} value={cursorMs}
          onChange={(e) => { setPlaying(false); setCursorMs(Number(e.target.value)) }}
          className="flex-1 accent-blue-600"
          aria-valuetext={dateLabel}
        />
        <span data-testid="precip-date" className="text-sm font-semibold text-neutral-700 w-16 text-right tabular-nums">{dateLabel}</span>
      </div>
      {/* pct is exposed for the scrubber position via the native range thumb */}
      <span className="sr-only">{Math.round(pct)}%</span>
    </div>
  )
}
```

- [ ] **Step 2: Add an entry affordance** in `bounties/page.tsx`. Add a `useState` `showTimelapse`, a button ("View report density timelapse") near the map, and conditionally render `<PrecipitationTimelapse bounties={...} spots={...} routes={...} onClose={() => setShowTimelapse(false)} />`. (Match the existing data wiring already passed to `CombinedMap` on that page.)

- [ ] **Step 3: Extend the e2e test.** Append to `site/e2e/tests/precipitation-heatmap.spec.ts`:

```ts
test('timelapse opens, plays, pauses, and scrubs', async ({ page }) => {
  await page.goto('/bounties')
  await page.getByRole('button', { name: /timelapse/i }).click()
  await expect(page.getByTestId('precip-timelapse')).toBeVisible()
  const date1 = await page.getByTestId('precip-date').textContent()
  await page.getByTestId('precip-play').click() // pause
  const scrubber = page.getByTestId('precip-scrubber')
  await scrubber.focus()
  await scrubber.press('Home')
  await expect(page.getByTestId('precip-date')).not.toHaveText(date1 ?? '')
})
```

- [ ] **Step 4: Run e2e + typecheck.** Run: `cd site && npx tsc --noEmit && npm run test:e2e -- precipitation-heatmap` — Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add site/src/components/map/PrecipitationTimelapse.tsx "site/src/app/(app)/bounties/page.tsx" site/e2e/tests/precipitation-heatmap.spec.ts
git commit -m "feat(map-web): full-screen report-density timelapse + scrubber"
```

---

### Task 11: App — `ts` features + native cursor filter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt` (timelapse constants)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt` (add `ts` to spot + heat-point features)
- Modify: `GccMap.android.kt`, `GccMap.ios.kt` (accept a `cursorMs: Long?`; apply filter to heat + dots layers)

**Interfaces:**
- Produces: `MapStyles.TIMELAPSE_WINDOW_DAYS = 14`, `TIMELAPSE_SPAN_DAYS = 90`, `TIMELAPSE_LOOP_MS = 8000L`; `ts` feature property; `GccMap(..., cursorMs: Long? = null)`.

- [ ] **Step 1: Add constants** to `MapStyles`:

```kotlin
    const val TIMELAPSE_WINDOW_DAYS = 14
    const val TIMELAPSE_SPAN_DAYS = 90
    const val TIMELAPSE_LOOP_MS = 8000L
```

- [ ] **Step 2: Add `ts` to features** in `GeoJsonLayers.kt`. In `buildSpotMarkersGeoJson` and `heatPointFeature`/`buildOverviewHeatPointsGeoJson`, include `"ts":${spot.createdAt.toEpochMilliseconds()}` in each feature's `properties`. (For `heatPointFeature`, thread a `tsMs: Long` parameter; pass `spot.createdAt.toEpochMilliseconds()` for spots and `0L` for activity coords.)

- [ ] **Step 3: Add the filter** in `GccMap.android.kt` and `GccMap.ios.kt`. Add a `cursorMs: Long? = null` parameter to the composable. Build the MapLibre-Compose filter expression when non-null:

```kotlin
// inside the composable, before the HeatmapLayer/dots layers:
val windowMs = MapStyles.TIMELAPSE_WINDOW_DAYS.toLong() * 86_400_000L
val timeFilter = cursorMs?.let { c ->
    org.maplibre.compose.expressions.dsl.all(
        org.maplibre.compose.expressions.dsl.lte(
            org.maplibre.compose.expressions.dsl.get("ts"), const(c.toDouble())),
        org.maplibre.compose.expressions.dsl.gt(
            org.maplibre.compose.expressions.dsl.get("ts"), const((c - windowMs).toDouble())),
    )
}
```

Pass `filter = timeFilter` to both spot `HeatmapLayer`s and the spot-dots `CircleLayer`. (Confirm the exact DSL helper names against the installed `org.maplibre.compose` version while reading the file in Step 0; the comparison/`all` operators exist — adjust to the available symbols.)

- [ ] **Step 0 (do first): Read** `GccMap.android.kt`, `GccMap.ios.kt`, `GeoJsonLayers.kt` to confirm the filter DSL symbols and the dots `CircleLayer` id, before Steps 2–3.

- [ ] **Step 4: Compile.** Run: `./gradlew :composeApp:compileDebugKotlinAndroid` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt composeApp/src/androidMain/kotlin/com/gcc/app/ui/map/GccMap.android.kt composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt
git commit -m "feat(map-app): ts features + native time-cursor filter"
```

---

### Task 12: App — full-screen Report Density timelapse screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ReportDensityTimelapseScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` (entry affordance)

**Interfaces:**
- Consumes: `GccMap(cursorMs = ...)` (Task 11), `MapStyles.TIMELAPSE_*`, `PrecipitationLegend` (Task 7).
- Produces: `ReportDensityTimelapseScreen(spots, onClose)`.

- [ ] **Step 1: Create the screen.** A full-screen `Box` with `GccMap(cursorMs = cursorMs, ...)`, the `PrecipitationLegend` (TopStart), a "Done" button (TopEnd, calls `onClose`), and a bottom scrubber row. Animate the cursor with a Compose `LaunchedEffect` loop:

```kotlin
@Composable
fun ReportDensityTimelapseScreen(
    spots: List<com.gcc.shared.model.CommunityReport>,
    onClose: () -> Unit,
) {
    val dayMs = 86_400_000L
    val spanMs = MapStyles.TIMELAPSE_SPAN_DAYS.toLong() * dayMs
    val end = remember { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() }
    val start = end - spanMs
    var cursorMs by remember { mutableStateOf(start) }
    var playing by remember { mutableStateOf(true) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var lastFrame = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = now - lastFrame
            lastFrame = now
            val next = cursorMs + (dt.toDouble() / MapStyles.TIMELAPSE_LOOP_MS * spanMs).toLong()
            cursorMs = if (next >= end) start else next
        }
    }

    Box(Modifier.fillMaxSize()) {
        GccMap(/* existing required params */ cursorMs = cursorMs)
        PrecipitationLegend(Modifier.align(Alignment.TopStart).padding(12.dp))
        Button(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Text("Done") }
        // Bottom scrubber
        Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.95f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playing = !playing }) { Text(if (playing) "❚❚" else "▶") }
            Slider(
                value = (cursorMs - start).toFloat(),
                onValueChange = { playing = false; cursorMs = start + it.toLong() },
                valueRange = 0f..spanMs.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                kotlinx.datetime.Instant.fromEpochMilliseconds(cursorMs)
                    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    .let { "${it.monthNumber}/${it.dayOfMonth}" },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
```

(Fill the `GccMap(...)` required params to match its existing call in `MapFullScreen.kt` — read that call site first. Add the necessary imports: `withFrameMillis`, layout/material3, `kotlinx.datetime.*`.)

- [ ] **Step 2: Add entry affordance** in `MapFullScreen.kt`: a `var showTimelapse by remember { mutableStateOf(false) }`, a play/“Timelapse” button overlay, and `if (showTimelapse) ReportDensityTimelapseScreen(spots = spots, onClose = { showTimelapse = false })`.

- [ ] **Step 3: Compile.** Run: `./gradlew :composeApp:compileDebugKotlinAndroid` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Visual check (simulator).** Open the map → tap Timelapse → confirm the heatmap + dots sweep across 90 days in an ~8s loop, play/pause works, and dragging the slider scrubs with a date readout.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ReportDensityTimelapseScreen.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat(map-app): full-screen report-density timelapse screen + scrubber"
```

---

## Self-Review

**Spec coverage:**
- §4 weather palette → Tasks 1 (web), 5 (app). ✓
- §5 feathered blobs → Task 1 (web radius), Task 6 (canvas outer ring). ✓
- §6 floating legend → Task 3 (web), Task 7 (app). ✓
- §7 scrim → Task 1+2 (web), Task 6 (native+canvas). ✓
- §8 layer toggle → Task 4 (web), Task 7 (app). ✓
- §9 file list → all listed files appear in a task. ✓
- §12 timelapse: `ts` features → Tasks 8/11; cursor filter on heatmap **and dots** → Tasks 9/11; rolling 14d window / 90d span / 8s loop constants → Tasks 8/11; full-screen mode + scrubber + play/pause + date → Tasks 10/12. ✓
- §10 out-of-scope (tile swap, other views, server aggregation) → not touched. ✓

**Type consistency:** `SPOT_SCRIM_PAINT` (web) Task 1→2/4; `SPOT_SCRIM_COLOR` (Kotlin) Task 5→6/7. `TIMELAPSE` (web) Task 8→9/10; `TIMELAPSE_*` (Kotlin) Task 11→12. `ts` feature property defined Tasks 8/11, consumed by filters Tasks 9/11. `cursorMs` prop/param: web `CombinedMap` Task 9 → consumed Task 10; Kotlin `GccMap` Task 11 → consumed Task 12. `PrecipitationLegend`/`PrecipitationTimelapse` (web) vs Kotlin screens are per-platform — no cross-reference.

**Placeholder scan:** No TBD/TODO; code shown for every code step. Tasks 6, 7, 11, 12 include explicit "read the file first" steps because exact surrounding lines and the `org.maplibre.compose` filter-DSL symbol names vary by installed version — the *changes* (exact colors, constants, filter logic, component code) are fully specified; the executor confirms the DSL symbol spelling against the resolved dependency.

**Known executor caveats:**
- The `org.maplibre.compose` filter DSL helper names (`all`/`lte`/`gt`/`get`) must be confirmed against the installed version (Task 11 Step 0) — the operators exist but spellings differ across releases.
- iOS/native builds are heavy; if no simulator is available, the `compile*` steps are the gating check and visual checks defer to a device pass.
- Web e2e needs at least one seeded spot with a `createdAt` inside the 90-day span for the timelapse/legend to render data.

**Note for executor:** iOS/native builds are heavy; if an emulator/simulator isn't available, the compile steps (`compileKotlinMetadata` / `compileDebugKotlinAndroid`) are the gating check and the visual checks can be deferred to a device pass.
