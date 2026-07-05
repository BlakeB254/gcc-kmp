/**
 * Property-conforming inner boundary generator — shared by the route-v2
 * admin regenerate action (/api/admin/routes/[routeId]/regenerate-inner-boundary)
 * and the block-proposal approve flow (/api/admin/block-proposals/[id]/approve),
 * so both paths compute the exact same interior/exterior split the mobile
 * app renders. Extracted as a pure function (ring in, MultiPolygon out) —
 * no DB access — so callers own their own lookup and persistence.
 *
 * Algorithm (multi-island, product direction 2026-07-03): samples the outer
 * ring every ~2m and ray-casts inward against real OSM building footprints.
 * Each CONTIGUOUS built-up stretch becomes its own exclusion island — a strip
 * from just street-side of the frontage line back to lot depth. Open
 * stretches (unfenced vacant lots) get NO island and stay fully scannable,
 * so a typical block in our current communities yields MULTIPLE islands.
 */

import { polygon as turfPolygon, featureCollection } from '@turf/helpers'
import turfBuffer from '@turf/buffer'
import { intersect as turfIntersect } from '@turf/intersect'
import { union as turfUnion } from '@turf/union'
import turfArea from '@turf/area'

export interface InnerBoundaryResult {
  multiPolygon: { type: 'MultiPolygon'; coordinates: number[][][][] }
  stats: { samples: number; simplifiedTo: number; buildingSegments: number; islands: number; rawIslands: number }
}

export async function generateInnerBoundaryFromRing(
  ring: number[][],
  options?: { setbackMeters?: number }
): Promise<InnerBoundaryResult> {
  if (!ring || ring.length < 4) {
    throw new Error('Boundary ring too small to inset')
  }

  const defaultSetback = clamp(options?.setbackMeters || 8, 4, 20)
  const frontageMargin = 1.5
  // Follow real frontage depth (storefront ~3m .. deep residential ~15m).
  // Floor raised 3→5m: islands were crowding the perimeter and choking the
  // walkable corridor in spots ('a bit too aggressive in some areas').
  const minSetback = 5
  const maxSetback = 15
  const frontageRange = 25 // a hit farther than this = effectively open
  // 30→20m: Chicago lots run ~28m street-to-alley; 30m strips crossed the
  // mid-block alley (scannable ground) and collided with strips from the
  // opposite street face.
  const backDepth = 20
  // Every island keeps at least this much clearance from the outer ring —
  // enforced geometrically (clip mask), not just via per-sample setback,
  // so corners can't pinch the corridor either.
  const corridorMinMeters = 5
  const minRunSamples = 3 // built stretches under ~6m are noise
  const gapMergeSamples = 2 // open gaps under ~4m get absorbed into a run

  // Meter-space conversion around the ring centroid.
  const cLat = ring.reduce((a, p) => a + p[1], 0) / ring.length
  const cLng = ring.reduce((a, p) => a + p[0], 0) / ring.length
  const mPerLat = 111_320
  const mPerLng = 111_320 * Math.cos((cLat * Math.PI) / 180)
  const toM = (p: number[]) => [(p[0] - cLng) * mPerLng, (p[1] - cLat) * mPerLat] as const
  const toLL = (x: number, y: number) => [x / mPerLng + cLng, y / mPerLat + cLat] as const

  // OSM buildings inside the block bbox (padded).
  const lats = ring.map((p) => p[1])
  const lngs = ring.map((p) => p[0])
  const pad = 0.0004
  const query = `[out:json][timeout:20];(way["building"](${Math.min(...lats) - pad},${Math.min(...lngs) - pad},${Math.max(...lats) + pad},${Math.max(...lngs) + pad}););out geom;`

  const buildingSegments: Array<readonly [readonly [number, number], readonly [number, number]]> = []
  // Full closed footprints too: every building on the map is itself an
  // exclusion (workers must never be REQUIRED to scan the middle of a
  // house), lightly buffered to respect fences/stoops; admins fine-tune
  // per-parcel in the studio.
  const buildingFootprints: number[][][] = []
  const overpass = await fetch('https://overpass-api.de/api/interpreter', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      // Overpass rejects requests with a generic/default User-Agent (406) —
      // it requires an identifying one, per usage policy.
      'User-Agent': 'GCC-InnerBoundaryGenerator/1.0 (community cleanup nonprofit; globalcommunitycleaners.org)',
    },
    body: `data=${encodeURIComponent(query)}`,
    signal: AbortSignal.timeout(25_000),
  })
  if (!overpass.ok) throw new Error(`overpass ${overpass.status}`)
  const data = (await overpass.json()) as { elements?: Array<{ geometry?: Array<{ lat: number; lon: number }> }> }
  for (const el of data.elements ?? []) {
    const geom = el.geometry ?? []
    for (let i = 0; i + 1 < geom.length; i++) {
      buildingSegments.push([toM([geom[i].lon, geom[i].lat]), toM([geom[i + 1].lon, geom[i + 1].lat])])
    }
    if (
      geom.length >= 4 &&
      geom[0].lat === geom[geom.length - 1].lat &&
      geom[0].lon === geom[geom.length - 1].lon
    ) {
      buildingFootprints.push(geom.map((g) => [g.lon, g.lat]))
    }
  }

  // Ring in meter space; winding for the inward normal.
  const pts = ring.slice(0, -1).map(toM)
  let area2 = 0
  for (let i = 0; i < pts.length; i++) {
    const [x1, y1] = pts[i]
    const [x2, y2] = pts[(i + 1) % pts.length]
    area2 += x1 * y2 - x2 * y1
  }
  const inward = area2 > 0 ? 1 : -1

  // Sample every ~2m; ray-cast inward, remembering per-sample frontage hits.
  type Sample = { x: number; y: number; nx: number; ny: number; hit: number | null }
  const samples: Sample[] = []
  for (let i = 0; i < pts.length; i++) {
    const [x1, y1] = pts[i]
    const [x2, y2] = pts[(i + 1) % pts.length]
    const dx = x2 - x1
    const dy = y2 - y1
    const len = Math.hypot(dx, dy)
    if (len < 0.5) continue
    const nx = (-dy / len) * inward
    const ny = (dx / len) * inward
    const steps = Math.max(1, Math.floor(len / 2))
    for (let sIdx = 0; sIdx < steps; sIdx++) {
      const t = (sIdx + 0.5) / steps
      const sx = x1 + dx * t
      const sy = y1 + dy * t
      let hit = Infinity
      for (const [[ax, ay], [bx, by]] of buildingSegments) {
        const d = raySegment(sx, sy, nx, ny, ax, ay, bx, by)
        if (d !== null && d < hit) hit = d
      }
      samples.push({ x: sx, y: sy, nx, ny, hit: hit <= frontageRange ? hit : null })
    }
  }
  if (samples.length < 4) {
    throw new Error('Boundary too small to inset')
  }

  // Built/open classification with small-gap absorption (circular).
  const built = samples.map((s) => s.hit !== null)
  const n = samples.length
  for (let i = 0; i < n; i++) {
    if (built[i]) continue
    // Absorb an open gap shorter than gapMergeSamples between built samples.
    let gapLen = 0
    let j = i
    while (gapLen <= gapMergeSamples && !built[j % n]) { gapLen++; j++ }
    if (gapLen <= gapMergeSamples && built[(i - 1 + n) % n] && built[j % n]) {
      for (let k = i; k < i + gapLen; k++) built[k % n] = true
    }
  }

  // Contiguous built runs (circular), each ≥ minRunSamples.
  const runs: number[][] = []
  const start = built.findIndex((b) => !b)
  if (start === -1) {
    runs.push(samples.map((_, i) => i)) // fully built block: one island
  } else {
    let run: number[] = []
    for (let k = 1; k <= n; k++) {
      const idx = (start + k) % n
      if (built[idx]) {
        run.push(idx)
      } else if (run.length > 0) {
        if (run.length >= minRunSamples) runs.push(run)
        run = []
      }
    }
    if (run.length >= minRunSamples) runs.push(run)
  }

  // One exclusion island per built run: frontage-following strip from the
  // cutoff line back to lot depth.
  const simplify = (line: Array<readonly [number, number]>) => {
    const out: Array<readonly [number, number]> = []
    for (let i = 0; i < line.length; i++) {
      const prev = out[out.length - 1] ?? line[Math.max(0, i - 1)]
      const next = line[Math.min(line.length - 1, i + 1)]
      const cur = line[i]
      const devi =
        Math.abs((next[0] - prev[0]) * (prev[1] - cur[1]) - (prev[0] - cur[0]) * (next[1] - prev[1])) /
        (Math.hypot(next[0] - prev[0], next[1] - prev[1]) || 1)
      if (devi > 0.3 || out.length === 0 || i === line.length - 1) out.push(cur)
    }
    return out
  }

  const islands: number[][][][] = []
  let totalSimplified = 0
  for (const run of runs) {
    let lastSetback = defaultSetback
    const front: Array<readonly [number, number]> = []
    const back: Array<readonly [number, number]> = []
    for (const idx of run) {
      const smp = samples[idx]
      const setback = smp.hit !== null ? clamp(smp.hit - frontageMargin, minSetback, maxSetback) : lastSetback
      lastSetback = setback
      front.push([smp.x + smp.nx * setback, smp.y + smp.ny * setback])
      back.push([smp.x + smp.nx * backDepth, smp.y + smp.ny * backDepth])
    }
    const frontS = simplify(front)
    const backS = simplify(back)
    if (frontS.length + backS.length < 4) continue
    const ring2 = [...frontS, ...backS.reverse()]
    ring2.push(ring2[0])
    totalSimplified += ring2.length
    islands.push([ring2.map(([x, y]) => [...toLL(x, y)])])
  }

  // ── Post-process: clip to the corridor mask, merge overlaps, drop noise ──
  //
  // 1. CLIP: every island is intersected with the outer ring shrunk by
  //    corridorMinMeters, guaranteeing a walkable band all the way around
  //    (including corners, which per-sample setbacks can't protect).
  // 2. MERGE: adjacent-edge runs meet at corners and overlap — overlapping
  //    exclusions must read as ONE solid exclusion, so islands are unioned
  //    until no two intersect.
  // 3. FILTER: slivers under ~25 m² are classification noise, not parcels.
  const rawCount = islands.length
  const closedOuter = ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1]
    ? ring
    : [...ring, ring[0]]
  let mask: ReturnType<typeof turfPolygon> | null = null
  try {
    const shrunk = turfBuffer(turfPolygon([closedOuter]), -corridorMinMeters, { units: 'meters' })
    if (shrunk && shrunk.geometry.type === 'Polygon') {
      mask = turfPolygon(shrunk.geometry.coordinates)
    }
  } catch {
    mask = null // degenerate outer — skip clipping rather than fail
  }

  type Feat = ReturnType<typeof turfPolygon>
  let feats: Feat[] = []
  // Building footprints (buffered ~1m for fence/stoop respect) join the
  // frontage strips as exclusion candidates before clip + union.
  const outerFeat = turfPolygon([closedOuter])
  for (const fp of buildingFootprints) {
    try {
      const raw = turfPolygon([fp])
      const inBlock = turfIntersect(featureCollection([raw, outerFeat]))
      if (!inBlock) continue
      const buffered = turfBuffer(raw, 1.0, { units: 'meters' })
      if (!buffered) continue
      if (buffered.geometry.type === 'Polygon') {
        islands.push(buffered.geometry.coordinates as number[][][])
      } else {
        for (const poly of buffered.geometry.coordinates) islands.push(poly as number[][][])
      }
    } catch {
      // skip malformed footprints
    }
  }
  for (const island of islands) {
    let feat: Feat
    try {
      feat = turfPolygon(island)
    } catch {
      continue
    }
    if (mask) {
      try {
        const clipped = turfIntersect(featureCollection([feat, mask]))
        if (!clipped) continue
        if (clipped.geometry.type === 'Polygon') {
          feats.push(turfPolygon(clipped.geometry.coordinates))
        } else {
          for (const poly of clipped.geometry.coordinates) feats.push(turfPolygon(poly))
        }
        continue
      } catch {
        // fall through with the unclipped island
      }
    }
    feats.push(feat)
  }

  // Union until stable: any pair that intersects becomes one solid exclusion.
  let merged = true
  while (merged && feats.length > 1) {
    merged = false
    outer: for (let i = 0; i < feats.length; i++) {
      for (let j = i + 1; j < feats.length; j++) {
        let u
        try {
          const inter = turfIntersect(featureCollection([feats[i], feats[j]]))
          if (!inter) continue
          u = turfUnion(featureCollection([feats[i], feats[j]]))
        } catch {
          continue
        }
        if (!u) continue
        const replacement: Feat[] = []
        if (u.geometry.type === 'Polygon') {
          replacement.push(turfPolygon(u.geometry.coordinates))
        } else {
          for (const poly of u.geometry.coordinates) replacement.push(turfPolygon(poly))
        }
        feats.splice(j, 1)
        feats.splice(i, 1, ...replacement)
        merged = true
        break outer
      }
    }
  }

  feats = feats.filter((f) => {
    try {
      return turfArea(f) >= 25
    } catch {
      return false
    }
  })

  const finalIslands = feats.map((f) => f.geometry.coordinates)

  return {
    multiPolygon: {
      type: 'MultiPolygon',
      coordinates: finalIslands,
    },
    stats: {
      samples: samples.length,
      simplifiedTo: totalSimplified,
      buildingSegments: buildingSegments.length,
      islands: finalIslands.length,
      rawIslands: rawCount,
    },
  }
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v))
}

/** Distance along ray (sx,sy)+(nx,ny)t to segment AB, or null if no hit in (0, 30]. */
function raySegment(
  sx: number, sy: number, nx: number, ny: number,
  ax: number, ay: number, bx: number, by: number
): number | null {
  const rx = bx - ax
  const ry = by - ay
  const denom = nx * ry - ny * rx
  if (Math.abs(denom) < 1e-9) return null
  const t = ((ax - sx) * ry - (ay - sy) * rx) / denom
  const u = ((ax - sx) * ny - (ay - sy) * nx) / denom
  if (t <= 0 || t > 30 || u < 0 || u > 1) return null
  return t
}
