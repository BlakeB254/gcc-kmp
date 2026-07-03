# Phase 4 — Map Tile Freshness Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement design spec §4.2 — a grid-cell overlay on the public map distinguishing never-scanned (grey) / scanned-fresh (transparent, base layer shows through) / scanned-stale >24h (amber hatched), backed by the already-live `GET /api/map/coverage` endpoint (Phase 0).

**Architecture:** A new client-safe grid-cell utility (pure math, no DB import, since it must run in the browser) computes which ~10m cells fall inside the current viewport and gives each a deterministic id matching the backend's `gridCell()` scheme. A new, separate component (`CoverageFreshnessOverlay.tsx`) fetches `/api/map/coverage` for the current bounds on move/zoom, diffs the returned cells against the full enumerated set (anything not returned = never-scanned), and renders a GeoJSON fill layer — restricted to close zoom only and off by default, per the spec's explicit lesson from the reverted full-earth wash mistake.

**Tech Stack:** Next.js, MapLibre GL via `react-map-gl/maplibre` (existing pattern in `CombinedMap.tsx`), no new dependencies.

## Global Constraints

- **Never render this overlay as a city-wide wash at low zoom.** Gate it behind `currentZoom >= SPOT_ZOOM.DOTS_MIN` (14), the same threshold the existing dot/heatmap crossfade already uses — confirmed from `site/src/lib/design-tokens.ts`.
- **Off by default, toggleable** — a new boolean state, not visible until a user opts in, per spec §4.2's explicit mitigation given this team's prior "planet-wide wash" regression (commits `608e213`/`86cb757`, unrelated to this new overlay but the lesson applies).
- **Do not import `site/src/lib/world-observations.ts` or `@/db` into any client (`'use client'`) component** — that file imports the Neon DB client, which must never end up in a browser bundle. Write a new, client-safe grid-math file with no DB import, even though it duplicates a few lines of math already in `world-observations.ts`/`geo-utils.ts`. The two must produce byte-identical `gridCell()` output for the same input, or the overlay and the backend will disagree about cell boundaries.
- **Keep `CombinedMap.tsx` from growing further** — it's already 835 lines. Put the new overlay's fetch/diff/render logic in its own file, imported and rendered by `CombinedMap.tsx`, not inlined into the existing component body.
- No unit-test framework in `site/` — verification is typecheck plus manual browser inspection (this repo has LightPanda headless browser access per the CDX platform's own tooling, useful for a real visual check of this specifically, since it's a rendering/visual feature).

---

### Task 1: Client-safe grid-cell utility

**Files:**
- Create: `site/src/lib/grid-cell-client.ts`

**Interfaces:**
- Produces: `gridCell(lat: number, lng: number, cellSizeMeters?: number): string` (must match `site/src/lib/world-observations.ts`'s `gridCell` byte-for-byte in output for the same input), `cellCenter(cellId: string, cellSizeMeters?: number): { lat: number; lng: number }`, and `enumerateCellsInBounds(bounds: { west: number; south: number; east: number; north: number }, cellSizeMeters?: number): string[]` — consumed by Task 2's overlay component.

- [ ] **Step 1: Read the exact functions being mirrored**

```bash
cat site/src/lib/world-observations.ts | head -20
grep -n "METERS_PER_DEG_LAT\|METERS_PER_DEG_LNG" site/src/lib/geo-utils.ts
```
Confirm the exact constants (`METERS_PER_DEG_LAT = 111_000`, `METERS_PER_DEG_LNG = 82_300` per this session's earlier grounding — re-confirm, don't assume they haven't changed) and the exact `gridCell`/`cellCenter` formulas already in `world-observations.ts` (both are plain math with no DB dependency in their bodies — only the file's top-level import of `db` makes the whole file unsafe for client bundles, not these two functions themselves).

- [ ] **Step 2: Write the client-safe file**

```typescript
/**
 * Client-safe duplicate of the grid-cell math in world-observations.ts.
 * That file imports the Neon DB client at module scope, which must never
 * end up in a browser bundle — so this file re-implements just the pure
 * math, kept byte-identical in output to the server-side gridCell/cellCenter
 * so the map overlay and the backend agree on cell boundaries.
 */
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

const CELL_SIZE_METERS = 10

export function gridCell(lat: number, lng: number, cellSizeMeters = CELL_SIZE_METERS): string {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const latIdx = Math.floor(lat / latStep)
  const lngIdx = Math.floor(lng / lngStep)
  return `${latIdx}:${lngIdx}`
}

export function cellCenter(cellId: string, cellSizeMeters = CELL_SIZE_METERS): { lat: number; lng: number } {
  const [latIdxStr, lngIdxStr] = cellId.split(':')
  const latIdx = Number(latIdxStr)
  const lngIdx = Number(lngIdxStr)
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  return {
    lat: (latIdx + 0.5) * latStep,
    lng: (lngIdx + 0.5) * lngStep,
  }
}

/**
 * Every cell id whose center falls within the given viewport bounds.
 * Restricted to close-zoom callers only (see Task 2) — at wide zoom this
 * would enumerate thousands of cells; the caller is responsible for that
 * gate, this function has no zoom awareness of its own.
 */
export function enumerateCellsInBounds(
  bounds: { west: number; south: number; east: number; north: number },
  cellSizeMeters = CELL_SIZE_METERS
): string[] {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const cells: string[] = []
  const minLatIdx = Math.floor(bounds.south / latStep)
  const maxLatIdx = Math.floor(bounds.north / latStep)
  const minLngIdx = Math.floor(bounds.west / lngStep)
  const maxLngIdx = Math.floor(bounds.east / lngStep)

  // Safety cap: if a caller passes overly wide bounds despite the zoom gate,
  // don't hang enumerating tens of thousands of cells.
  const cellCount = (maxLatIdx - minLatIdx + 1) * (maxLngIdx - minLngIdx + 1)
  if (cellCount > 5000) {
    console.warn('enumerateCellsInBounds: bounds too wide, returning empty (expected close-zoom caller)')
    return []
  }

  for (let latIdx = minLatIdx; latIdx <= maxLatIdx; latIdx++) {
    for (let lngIdx = minLngIdx; lngIdx <= maxLngIdx; lngIdx++) {
      cells.push(`${latIdx}:${lngIdx}`)
    }
  }
  return cells
}
```

- [ ] **Step 3: Typecheck**

```bash
cd site && npx tsc --noEmit
```
Expected: no new errors.

- [ ] **Step 4: Manual verification — confirm byte-identical output vs. the server-side function**

```bash
cd site && npx tsx -e "
import { gridCell as clientGridCell, cellCenter as clientCellCenter } from './src/lib/grid-cell-client'
import { gridCell as serverGridCell } from './src/lib/world-observations'

const testPoints = [[41.8617, -87.6851], [41.87, -87.68], [42.0, -87.0]]
for (const [lat, lng] of testPoints) {
  const clientResult = clientGridCell(lat, lng)
  const serverResult = serverGridCell(lat, lng)
  console.log(lat, lng, '-> client:', clientResult, 'server:', serverResult, clientResult === serverResult ? 'MATCH' : 'MISMATCH')
}

const center = clientCellCenter('464756:-721607')
console.log('cellCenter roundtrip:', JSON.stringify(center))
console.log('gridCell(center) should match original id:', clientGridCell(center.lat, center.lng))
"
```
Expected: every test point prints `MATCH`, not `MISMATCH` — this is the one thing that would silently break the whole overlay if wrong (client and server disagreeing about cell boundaries). If any point mismatches, stop and fix before proceeding — do not continue to Task 2 with a mismatching grid.

- [ ] **Step 5: Commit**

```bash
git add site/src/lib/grid-cell-client.ts
git commit -m "feat(map): add client-safe grid-cell utility for the coverage-freshness overlay"
```

---

### Task 2: Coverage freshness overlay component + integration

**Files:**
- Create: `site/src/components/map/CoverageFreshnessOverlay.tsx`
- Modify: `site/src/components/map/CombinedMap.tsx` (integration point only — render the new component, add the toggle state, don't touch anything else in this large file)

**Interfaces:**
- Consumes: `enumerateCellsInBounds`/`cellCenter` (Task 1), `GET /api/map/coverage?bbox=...` (already live, Phase 0), `SPOT_ZOOM.DOTS_MIN` (`site/src/lib/design-tokens.ts`, already exists).
- Produces: `<CoverageFreshnessOverlay bounds={...} zoom={...} visible={boolean} />` — a self-contained component `CombinedMap.tsx` renders, taking the current viewport state as props rather than managing its own map subscription (keeps it testable/reasoned-about in isolation).

- [ ] **Step 1: Read `CombinedMap.tsx`'s `onMove` handler and zoom-state pattern**

```bash
grep -n "onMove\|currentZoom\|mapRef" site/src/components/map/CombinedMap.tsx
```
Confirm the exact `onMove` signature and whether a `mapRef`/similar already exists to call `.getBounds()` on, or whether this needs to be added. `react-map-gl/maplibre`'s `onMove` event object exposes `event.target` (the underlying maplibre-gl `Map` instance) — `event.target.getBounds()` returns a `LngLatBounds` with `.getWest()`/`.getSouth()`/`.getEast()`/`.getNorth()`.

- [ ] **Step 2: Write `CoverageFreshnessOverlay.tsx`**

```typescript
'use client'

import { useEffect, useState } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { enumerateCellsInBounds, cellCenter } from '@/lib/grid-cell-client'

const STALE_COLOR = '#d97706' // amber-600
const NEVER_SCANNED_COLOR = '#9ca3af' // grey-400
const MIN_ZOOM_TO_SHOW = 14 // matches SPOT_ZOOM.DOTS_MIN — close zoom only, never a wide-area wash

interface Bounds {
  west: number
  south: number
  east: number
  north: number
}

interface CoverageFreshnessOverlayProps {
  bounds: Bounds
  zoom: number
  visible: boolean
}

interface CoverageCell {
  locationCellId: string
  lat: number
  lng: number
  state: 'fresh' | 'stale'
}

/**
 * Renders never-scanned (grey) and stale (amber) cells as small filled
 * squares over the map, restricted to close zoom (>= MIN_ZOOM_TO_SHOW) and
 * gated by the `visible` toggle — deliberately never a wide-zoom wash, per
 * design spec §4.2's explicit lesson from this team's reverted full-earth
 * tint regression. Fresh cells and areas outside any coverage data render
 * nothing, letting the existing heatmap/dot layers show through unchanged.
 */
export function CoverageFreshnessOverlay({ bounds, zoom, visible }: CoverageFreshnessOverlayProps) {
  const [scannedCells, setScannedCells] = useState<CoverageCell[]>([])
  const shouldRender = visible && zoom >= MIN_ZOOM_TO_SHOW

  useEffect(() => {
    if (!shouldRender) return
    const bboxParam = `${bounds.west},${bounds.south},${bounds.east},${bounds.north}`
    let cancelled = false
    fetch(`/api/map/coverage?bbox=${bboxParam}`)
      .then((res) => res.json())
      .then((data) => {
        if (!cancelled && data.success) setScannedCells(data.cells)
      })
      .catch(() => {
        if (!cancelled) setScannedCells([])
      })
    return () => {
      cancelled = true
    }
  }, [shouldRender, bounds.west, bounds.south, bounds.east, bounds.north])

  if (!shouldRender) return null

  const scannedCellIds = new Set(scannedCells.map((c) => c.locationCellId))
  const allCellIds = enumerateCellsInBounds(bounds)

  const features = [
    // Never-scanned cells: anything enumerated but not returned by the API
    ...allCellIds
      .filter((id) => !scannedCellIds.has(id))
      .map((id) => cellToFeature(id, 'never')),
    // Stale cells: returned by the API with state === 'stale'
    ...scannedCells
      .filter((c) => c.state === 'stale')
      .map((c) => cellToFeature(c.locationCellId, 'stale')),
  ]

  const geojson = {
    type: 'FeatureCollection' as const,
    features,
  }

  return (
    <Source id="coverage-freshness" type="geojson" data={geojson}>
      <Layer
        id="coverage-freshness-fill"
        type="fill"
        paint={{
          'fill-color': ['match', ['get', 'freshness'], 'never', NEVER_SCANNED_COLOR, 'stale', STALE_COLOR, 'transparent'],
          'fill-opacity': ['match', ['get', 'freshness'], 'never', 0.35, 'stale', 0.3, 0],
        }}
      />
    </Source>
  )
}

function cellToFeature(cellId: string, freshness: 'never' | 'stale') {
  const { lat, lng } = cellCenter(cellId)
  const halfCellDeg = 0.00005 // ~5m at this latitude — small square per cell, not exact but visually correct at this zoom
  return {
    type: 'Feature' as const,
    properties: { freshness },
    geometry: {
      type: 'Polygon' as const,
      coordinates: [[
        [lng - halfCellDeg, lat - halfCellDeg],
        [lng + halfCellDeg, lat - halfCellDeg],
        [lng + halfCellDeg, lat + halfCellDeg],
        [lng - halfCellDeg, lat + halfCellDeg],
        [lng - halfCellDeg, lat - halfCellDeg],
      ]],
    },
  }
}
```

Note the `halfCellDeg` approximation in `cellToFeature` — it's a fixed degree offset, not computed from `METERS_PER_DEG_LAT`/`LNG` like the rest of this file. Fix this before considering the task done: compute the actual half-cell size in degrees using the same constants `grid-cell-client.ts` uses, so squares are sized correctly rather than approximately (at Chicago's latitude the lat/lng meter-per-degree ratio differs meaningfully, and a fixed value will produce visibly non-square or misaligned cells).

- [ ] **Step 3: Wire the toggle state and viewport tracking into `CombinedMap.tsx`**

Add a new state near the existing `heatmapVisible` (`site/src/components/map/CombinedMap.tsx:139`):

```typescript
const [coverageOverlayVisible, setCoverageOverlayVisible] = useState(false) // off by default, per spec §4.2
const [mapBounds, setMapBounds] = useState<{ west: number; south: number; east: number; north: number } | null>(null)
```

Extend the existing `onMove` handler (`site/src/components/map/CombinedMap.tsx:555`) to also capture bounds — read the exact current handler first and add to it rather than replacing it:

```typescript
onMove={(event) => {
  setCurrentZoom(event.viewState.zoom)
  const b = event.target.getBounds()
  setMapBounds({ west: b.getWest(), south: b.getSouth(), east: b.getEast(), north: b.getNorth() })
}}
```

Render the new component (place it near the other `<Source>` blocks, order doesn't matter much for a fill layer but keep it visually grouped with the heatmap/spots sources for readability):

```typescript
{mapBounds && (
  <CoverageFreshnessOverlay bounds={mapBounds} zoom={currentZoom} visible={coverageOverlayVisible} />
)}
```

Add the import at the top of the file: `import { CoverageFreshnessOverlay } from './CoverageFreshnessOverlay'`.

- [ ] **Step 4: Add a toggle control**

Find wherever `heatmapVisible`'s toggle UI (if any exists — search for `setHeatmapVisible` usage) lives, and add a sibling toggle for `coverageOverlayVisible` following the same UI pattern (button/checkbox, whatever style the existing toggle uses). If no existing UI toggles `heatmapVisible` (it may be permanently true with no user-facing control), add a minimal new button near the map controls, labeled something like "Show scan coverage" — check `site/src/components/map/` for any existing map-control/legend component (e.g. `PrecipitationLegend.tsx`, mentioned in this session's earlier grounding) to match its visual style rather than inventing a new one.

- [ ] **Step 5: Fix the `halfCellDeg` approximation flagged in Step 2**

Replace the fixed `0.00005` with a computed value using the same constants as `grid-cell-client.ts`:

```typescript
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'
// ...
function cellToFeature(cellId: string, freshness: 'never' | 'stale') {
  const { lat, lng } = cellCenter(cellId)
  const cellSizeMeters = 10
  const halfLatDeg = (cellSizeMeters / METERS_PER_DEG_LAT) / 2
  const halfLngDeg = (cellSizeMeters / METERS_PER_DEG_LNG) / 2
  return {
    type: 'Feature' as const,
    properties: { freshness },
    geometry: {
      type: 'Polygon' as const,
      coordinates: [[
        [lng - halfLngDeg, lat - halfLatDeg],
        [lng + halfLngDeg, lat - halfLatDeg],
        [lng + halfLngDeg, lat + halfLatDeg],
        [lng - halfLngDeg, lat + halfLatDeg],
        [lng - halfLngDeg, lat - halfLatDeg],
      ]],
    },
  }
}
```

- [ ] **Step 6: Typecheck**

```bash
cd site && npx tsc --noEmit
```

- [ ] **Step 7: Manual browser verification**

Start the dev server and visually inspect using this platform's LightPanda headless browser (per this session's CDX-platform context: `lightpanda fetch --dump markdown URL` for a one-shot check, or the CDP server for interactive inspection if already running — check `systemctl --user status lightpanda` first since the service is disabled by default and needs starting):

```bash
cd site && npm run dev &
sleep 3
```
Navigate to the bounties/map page at a zoom level >= 14 over an area with some known scanned cells (from this session's earlier Phase 0 verification, real coverage data exists at roughly lat 41.87, lng -87.68 — check `GET /api/map/coverage?bbox=-87.7,41.8,-87.6,41.9` first to confirm what's actually in the live DB before deciding where to visually check). Confirm: grey cells appear over unscanned areas at close zoom only, the overlay disappears entirely below zoom 14, and toggling the new control on/off actually shows/hides it. Take a screenshot if the tooling supports it, to visually confirm before considering this done — a map rendering feature isn't verified by typecheck alone.

Stop the dev server when done.

- [ ] **Step 8: Commit**

```bash
git add site/src/components/map/CoverageFreshnessOverlay.tsx site/src/components/map/CombinedMap.tsx
git commit -m "feat(map): add tile freshness overlay (grey never-scanned, amber stale), close-zoom-only and off by default"
```

---

## Self-Review Notes

- **Spec coverage:** implements design spec §4.2 (tile freshness overlay) and §4.3 (backed by the already-live `/api/map/coverage` endpoint). Does not implement §4.1 (photo-derived litter billboard rendering) — that's a separate, larger piece of work (requires photo cropping/segmentation, not just this overlay's grid math) and is out of scope for this plan.
- **Placeholder scan:** the `halfCellDeg` approximation introduced in Task 2 Step 2 is explicitly flagged and fixed in Step 5 within the same task — not left as a silent placeholder across task boundaries.
- **Type consistency:** `gridCell`/`cellCenter` in `grid-cell-client.ts` (Task 1) must produce identical output to `world-observations.ts`'s versions — verified explicitly in Task 1 Step 4, not assumed. `CoverageCell`'s `state: 'fresh' | 'stale'` matches the shape `GET /api/map/coverage` actually returns (confirmed in this session's earlier Phase 0 work).
