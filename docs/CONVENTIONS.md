# Cross-cutting conventions (must stay in lockstep)

These are contracts that span the client/server (or Kotlin/TypeScript) boundary and have **no compiler to enforce them**. Each has bitten us at least once. When you touch one side, update the other and its test.

## 1. Coordinates are `(lng, lat)` — longitude FIRST

The shared `Coordinates` type (`shared/.../model/GeoTypes.kt`) and all GeoJSON in this repo are **`[lng, lat]`** ordered (GeoJSON spec). Latitude-first is the natural instinct and has caused a real transposition bug that put every pin off-map (`WorldObservation.kt` KDoc).

- **Always construct with named args:** `Coordinates(lat = …, lng = …)`.
- GeoJSON ring vertices are `[lng, lat]`. The point-in-polygon helper for footprints is `pointInGeoJsonRing(lng, lat, ring)` — deliberately distinct from `pointInPolygon(lat, lng, pairs)` so the two orderings never cross.

## 2. `gridCell` must be byte-equal across Kotlin and TypeScript

The ~10m coverage cell id is produced independently on both sides and **must match exactly** — it names the R2 `raw/{cellId}/` path the DGX pipeline groups by, and the client/server coverage rollup joins on it.

- Kotlin: `shared/.../geo/GridCell.kt::gridCell10m`
- TypeScript: `site/src/lib/world-observations.ts::gridCell` (+ `grid-cell-client.ts`)
- Both use `METERS_PER_DEG_LAT = 111000`, `METERS_PER_DEG_LNG = 82300` — **frozen constants, NOT `cos(lat)`**. There's a frozen-value test; don't "improve" the projection on one side.

## 3. Litter catalog mirror

`site/src/lib/litter-footprints.ts` (server) ↔ `TrashItemCatalog.kt` (client) describe the same item types (labels, plurals, weight/size estimates). Item-type strings are canonicalized at all producers so they don't fall through to generic dots. Keep the two lists aligned.

## 4. Schema ownership (see root `CLAUDE.md`)

`site/src/db/schema.ts` is the ONLY place tables are declared. Any change must, in the same PR, update the Ktor `Tables.kt` mirror (for every column the server reads/writes) and any shared `@Serializable` DTO. Migrations are hand-written + applied via `psql` (see `CLAUDE.md` — `db:push`/`generate` are not trusted).

## 5. MapLibre overlay projection

Any Compose `Canvas` drawn over a maplibre-compose map must bias zoom by `MapStyles.mapOverlayZoomBias(density) = log2(density × 2)` (×density: MapLibre scale is per-point, Canvas is physical px; ×2: MapLibre zoom is 512-tile). Wrong bias renders geometry concentric-but-scaled. See `MapStyles.kt` KDoc.

---

These pairs are individually documented at each site; this file is the **checklist** so you remember the *other* side exists. New cross-boundary contract? Add it here.
