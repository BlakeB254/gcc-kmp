# Problem Areas — One Shape Language, Real Evidence

**Date:** 2026-07-09 · **Status:** Design approved, not implemented
**Scope:** Spec 1 of 5 (see [Decomposition](#decomposition))

## Why

Field report (Blake, build 140 simulator + device): the scanned-coverage blobs read well,
but the litter areas are "differently colored rectangles and a circle — I don't know what
any of it means, none of it is descriptive or has images when clicked."

Investigation showed the complaint is **not** a capture gap. Prod holds 209 litter
observations, 195 with a working `mediaUrl` (three sampled at random, all `HTTP 200`).
`ObservationInfoCard` (`MapFullScreen.kt:1925`) already renders them with tap-to-enlarge.
The photos exist. They are unreachable.

## Defects (all verified against prod data or code)

| # | Defect | Evidence |
|---|---|---|
| D1 | A cell is "dirty" only when near an unresolved **spot/report**; scan-found litter never colours a cell | `GeoJsonLayers.kt:930-937` — `dirty = fresh.filter { hasSpotNear(...) }`. 209 litter observations produce zero red. |
| D2 | Litter is drawn instead as true-scale footprint rectangles — unreadable, near-unhittable tap targets | `GccMap.ios.kt:1158-1272` |
| D3 | Vegetation (green circle = a tree) and construction (grey rect) footprints are drawn with no legend entry | domain colours `0xFF16A34A`, `0xFF6B7280` |
| D4 | `CoverageCellInfoCard` receives `observations` but renders no photos | `MapFullScreen.kt:1827` — `Text` only, no `PhotoStrip` |
| D5 | Legend describes coverage states only; nothing describes footprints | `MapFullScreen.kt:1765` |
| D6 | Doc comment contradicts code: claims dirty/stale stay per-cell quads | `GeoJsonLayers.kt:880` vs `:927` (`qualityCollection` → `outlineFeatures` whenever `useOutlines`, which is always) |
| D7 | **Live prod bug.** `/api/map/coverage`'s observed-only-litter join filters `state='dirty'` with **no `domain` filter**, so 18 construction + 6 vegetation rows are counted as litter and inflate rescan priority | `site/src/app/api/map/coverage/route.ts:87-96`; confirmed by query |

D1 is the headline: **the map is structurally incapable of showing scan-found litter as a
problem area.** Everything downstream depends on fixing it.

D6 also means the shapes Blake liked (the big olive blob) and disliked (the small
rectangles) come from *different systems*: coverage states already merge into organic
outlines; only the observation footprints are rectangles.

## Design

### A. Data & derivation

`MapCoverageCell` (`shared/.../model/ScanCoverage.kt:47`) gains two **additive** read-time
fields, defaulted so cached/legacy responses still decode — exactly the precedent set by
`priority` ("computed server-side at read time — no schema migration"):

```kotlin
val litterCount: Int = 0            // current dirty litter observations in this cell
val litterWeightGrams: Double = 0.0 // Σ attributes.estimatedWeightGrams
```

`/api/map/coverage` computes both with **one bbox-scoped aggregate query** grouped by
`location_cell_id` — not a per-cell join. This reuses the exact pattern and justification
already written into that route for `observedOnlyCells` (route.ts:11-21): both
`cellCoverageSummary.locationCellId` and `worldObservations.locationCellId` are
`gridCell()` ids, so the result is a JS Map + cheap membership check per row.

The predicate is:

```sql
domain = 'litter' AND is_current AND state = 'dirty'
```

> **`domain` is load-bearing, not decorative.** `construction` and `vegetation` rows also
> carry `state='dirty'` (18 and 8 rows in prod today). Filtering on `state` alone paints
> every tree as a mess. **Fixing D7 means adding this same `domain='litter'` filter to the
> existing `observedOnlyRows` query**, which omits it today.

Client derivation in `buildCoverageStateGeoJsons` (`GeoJsonLayers.kt:861`) changes from a
spot-only test to a union, with **litter winning over stale**:

```
problem = litterCount > 0 || hasSpotNear(lat, lng)   // red, severity-tiered
stale   = state == "stale" && !problem               // amber
clean   = fresh && !problem                          // green, alpha by quality
fog     = unscanned                                  // grey
```

This reorders precedence. Today `stale` is computed independently of dirtiness
(`GeoJsonLayers.kt:936-937`), so a stale cell containing litter renders amber. Under the
new rule it renders red, and staleness moves into the card text ("seen 3 days ago").
Rationale: known trash is actionable; staleness is only a hint the data may be old.

`hasSpotNear` (12m radius, unresolved `CommunityReport`) is retained so human reports keep
lighting up the map. Problem = scan-found litter **OR** human report.

### B. Rendering

**Constraint that drives the whole design:** merged marching-squares outlines destroy
per-cell attributes. `outlineFeatures(cells)` collapses many cells into one polygon per
connected component, so a merged blob cannot carry a per-cell `severity` or `quality`
property. Data-driven `interpolate` on `fillColor` — how the choropleth works — is
therefore unavailable *inside* a blob.

The file's existing answer is to **group cells, then merge each group separately**; that is
precisely why `clean`/`cleanGrazed`/`seen` are three collections and not one. We follow it.

**Severity → hue.** Problem cells bucket into three tiers by `litterWeightGrams`, falling
back to `litterCount` when weight is absent (unweighed observations exist). Each tier emits
its own merged outline:

| Tier | Threshold (grams) | Fill | Outline |
|---|---|---|---|
| light | `> 0` and `< 2000` | `0xFFF59E0B` amber-red | `0xFFB45309` |
| moderate | `>= 2000` and `< 15000` | `0xFFEF4444` red | `0xFFB91C1C` |
| severe | `>= 15000` | `0xFF991B1B` deep red | `0xFF7F1D1D` |

Thresholds are first-cut and MUST be validated against the real distribution of
`litterWeightGrams` per cell before merge; a tier that never occurs is a bug, not a
subtlety. Reuses existing palette entries where possible (`0xFFEF4444`, `0xFFF59E0B`,
`0xFFB91C1C`, `0xFFB45309` all already in `GccMap.ios.kt`).

Three `FillLayer` + `LineLayer` pairs, each `const()`-coloured, each gated on non-empty
(the established "no source/layer allocation when there's nothing to show" rule).

> **Never use `interpolateHcl`.** MapLibre Native has no `interpolate-hcl` operator; it
> raises an uncatchable ObjC `NSException` → SIGABRT. This crashed TestFlight 139. Use
> `interpolate` with `linear()`.

**Quality → alpha**, applied to the *clean* tiers, which is where "panned across vs. walked
up close" actually lives. The machinery exists; `seen` at α0.05 is effectively invisible,
which is why the gradient was never perceived. Retune and verify on device:

| Quality | Meaning | α today | α proposed |
|---|---|---|---|
| `seen` | sighted at distance (panned across) | 0.05 | 0.10 |
| `grazed` | walked past, sweep-only | 0.16 | 0.22 |
| `close` | approached, phone angled at ground (≤4m ray) | 0.36 | 0.40 |

**Explicit non-goal:** severity × quality as a 9-way cross product. It costs 18 layers for
negligible benefit — quality matters on ground you cleaned, severity on ground that's
dirty.

**Removals.** Litter footprints are no longer drawn at close zoom (superseded by problem
blobs). Vegetation and construction footprint layers are deleted outright. All three
domains' rows remain in the DB for the world model and the DGX pipeline; only the map
layers go. Net: `GccMap.ios.kt` **removes 12** footprint layers (3 domains × {fill,outline}
× {diagnosed,observed}) and **adds 6** problem layers (3 severity tiers × {fill,outline}).

### C. Interaction & legend

**Tap.** Merged outlines carry no `cellId`, so taps already resolve through the map-level
hit-test `coverageCellIdAt(lat, lng)` = `gridCell10m` (`GccMap.ios.kt:317`,
`CoverageCellHitTest.kt:12`). That mechanism is unchanged and now becomes the *only* path,
which removes the "miss the rectangle by 3px and fall through to the tile below" failure
that produced Blake's `"needs re-scan … no litter here"` card.

`CoverageCellInfoCard` (`MapFullScreen.kt:1827`) is rewritten into a single **problem-area
card** that answers "what is here, how bad, how do I know":

- **Photos.** A `PhotoStrip` of up to 4 `mediaUrl`s from observations in the tapped cell,
  reusing the component `ObservationInfoCard`/`SpotInfoCard` already use, with the same
  tap-to-enlarge gallery. Selection order per the Pillar 8/9 addendum: closest capture
  distance, then highest confidence, then most recent. **Fixes D4.**
- **What.** Item types + counts via `TrashItemCatalog.infoFor` ("3 bottles, 1 tire").
- **How bad.** Estimated total weight, severity tier label.
- **How fresh.** `lastScannedAt` relative ("scanned 3 days ago") and scan quality
  ("close-scanned" / "seen from a distance — needs a closer look").
- **Clean cells** keep today's copy; **fog cells** keep "never scanned".

`ObservationInfoCard` is retained for now but becomes unreachable from the map (its
footprint layers are gone). It is **not** deleted in this pass — Spec 3's manual-identify
flow may reuse it. Flagged so it isn't mistaken for live code.

**Legend** (`ScanCoverageLegend`, `MapFullScreen.kt:1765`) is rewritten to describe what is
actually drawn, resolving D3/D5:

```
Problem area   · light / moderate / severe   (3 red swatches)
Scanned close  · full green
Scanned near   · lighter green
Seen at distance · faintest green
Stale · re-scan  · amber
Never scanned    · grey
Tap any area for photos and details
```

### D. Error handling & degradation

- `litterCount`/`litterWeightGrams` default to `0`, so a **stale server** (pre-deploy)
  degrades to exactly today's behaviour: problem = `hasSpotNear` only. No crash, no blank
  map. This is why the fields are additive rather than a new response shape.
- A cell with `litterCount > 0` but `litterWeightGrams == 0` (unweighed observations) falls
  to the count-based tier rule rather than rendering as `light` by accident.
- Photos are best-effort: a `mediaUrl` that 404s renders the `PhotoStrip` placeholder; the
  card still shows counts and weight. Never block the card on image load.
- The bbox aggregate query is bounded (`LIMIT`, mirroring the existing `.limit(5000)`); an
  over-limit bbox degrades to partial severity data, never an error.
- If `observations` for the tapped cell is empty but the cell is `problem` via
  `hasSpotNear`, the card shows the report's photos (`spot.mediaUrls`) instead — the union
  must not produce an evidence-less red blob.

### E. Testing

Server (`node:test` + `tsx`, per repo convention — not vitest/jest):
- `domain='litter'` filter excludes construction/vegetation. **Regression test for D7**,
  asserted against the real shape of the data (a `vegetation` row with
  `state='dirty', diagnosticStatus='observed'` must not appear in the litter set).
- `litterCount`/`litterWeightGrams` aggregate correctly per `location_cell_id`.
- Cells with no observations return `0`/`0.0`, not `null`.

Client (`composeApp` `commonTest` — must compile for iOS, so no `JdbcSqliteDriver`):
- `buildCoverageStateGeoJsons`: a cell with `litterCount > 0` lands in a problem tier and
  **not** in `stale`, even when `state == "stale"` (pins the precedence reorder).
- A cell with `hasSpotNear` and `litterCount == 0` still lands in problem (union preserved).
- Severity tiering: boundary values (0, 1999, 2000, 14999, 15000) land in the expected tier.
- `litterWeightGrams == 0 && litterCount > 0` → count-based tier, not `light`.
- Merged-outline grouping emits one collection per tier and omits empty tiers.

Device (the real gate — none of the above proves legibility):
- Walk a North Lawndale block; confirm problem blobs appear where the 26 `route_scan`
  litter cells are, in blob form, with photos on tap.
- Confirm the `seen`/`grazed`/`close` alpha gradient is perceptible in daylight.
- Confirm no rectangles remain at close zoom.

### F. Sequencing

1. Server: `domain` filter (D7 — ships alone, it's a live bug), then the aggregate + two
   additive fields.
2. Shared: `MapCoverageCell` fields.
3. Client: derivation union + precedence; severity tiering; layer add/remove.
4. Client: card rewrite + legend.
5. Device walk.

Step 1 is independently shippable and should not wait for the rest.

## Non-goals (this spec)

- Native frame capture / `AreaCaptureFrameUploader` wiring. **Not needed** — the photos
  already exist and the depth/pose chunk path already uploads to R2 via `ArSessionRecorder`
  + `CaptureChunkUploader`.
- Any change to detection thresholds, AR capture guidance, or Object Capture.
- Lap-1 coverage gating and the `OCCLUDED` cell state.
- A DGX-side consumer.
- Web-map parity. The additive fields make it possible; doing it is a follow-up.

## Decomposition

This is Spec 1 of 5 agreed with Blake:

1. **Problem areas — one shape language, real evidence** ← this doc
2. **Lap-1 close-coverage gate** + `OCCLUDED` cell state. Denominator = cells within ~4m of
   the walked path, minus indoor-gate purges and `blocks.inner_boundary` exclusion zones.
   Hard block, no "complete anyway" button; cells the worker approached but could never get
   a close ray into auto-drop as `OCCLUDED` and are reported to admin + lap 2.
   **Note:** this *removes* the `completeScout(force = true)` escape shipped in PR #137
   (`RouteScanViewModel.kt:895`), which exists specifically to prevent a permanent
   dead-end. The `OCCLUDED` rule is what makes removing it safe; it must land first.
3. **Detection quality.** Raise `TRASH_CONFIDENCE` (`TrashDetectionRules.kt:16`, currently
   `0.35f`) and/or `MarkerPromotionTracker.streakTicks` (currently 2), **together with** a
   manual "Identify manually" button. Raising the threshold trades recall for precision;
   without a manual path a stricter threshold makes the app worse at its job. Also: AR
   guidance to approach, frame, and hold still before capture (no motion/blur check exists
   today — `ScanQualityScorer` is feedback-only and gates nothing).
4. **Large messes** → comprehensive scan + generated asset. Object Capture machinery is
   intact (`ObjectCapturePhotogrammetry.swift`, USDZ + bounds, LiDAR-gated) but its entry
   point was deliberately deleted (`SpotScreen.kt:1106`, "Blake: not needed"). Re-enabling
   reverses that call.
5. **DGX consumer.** `ArSessionRecorder.swift:251` already reads `smoothedSceneDepth` +
   `camera.intrinsics` + pose into HEVC chunks & JSONL sidecars, uploaded to
   `raw/{cellId}/{sessionId}/capture/chunk-N.*`. The feed exists; the consumer is Phase 3
   and unbuilt (`docs/governance/production-readiness-and-scaling.md`). `AreaCaptureFrameUploader`
   is a second, unwired capture path — a duplicate to resolve, not the missing link.

## Open risks

- **Severity thresholds are guesses.** Validate against the real per-cell weight
  distribution before merge; adjust so all three tiers occur in North Lawndale.
- **Precedence reorder is user-visible.** Cells that were amber become red. Intended, but
  it will look like "more mess appeared" on first launch. Worth a release note.
- **`spot_report` dominates.** 140 of 166 problem cells come from `spot_report`, only 26
  from `route_scan`. The map will be mostly report-driven until scanning volume grows;
  don't read early screenshots as proof the scan path works.
- **α retune is a guess until seen in daylight.** 0.10 may still be invisible on a bright
  phone outdoors.
