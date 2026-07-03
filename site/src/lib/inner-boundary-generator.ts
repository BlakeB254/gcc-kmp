/**
 * Property-conforming inner boundary generator — shared by the route-v2
 * admin regenerate action (/api/admin/routes/[routeId]/regenerate-inner-boundary)
 * and the block-proposal approve flow (/api/admin/block-proposals/[id]/approve),
 * so both paths compute the exact same interior/exterior split the mobile
 * app renders. Extracted as a pure function (ring in, MultiPolygon out) —
 * no DB access — so callers own their own lookup and persistence.
 *
 * Algorithm: samples the outer ring every ~2m, ray-casts inward against real
 * OSM building footprints, and places the cutoff just street-side of the
 * first frontage hit (clamped 4m..setbackMeters). Where nothing is built,
 * falls back to a parallel setback — unfenced vacant lots stay scannable.
 */

export interface InnerBoundaryResult {
  multiPolygon: { type: 'MultiPolygon'; coordinates: number[][][][] }
  stats: { samples: number; simplifiedTo: number; buildingSegments: number }
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
  const minSetback = 4

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

  // Sample every ~2m; ray-cast inward for the nearest frontage.
  const inner: Array<readonly [number, number]> = []
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
      const setback = Number.isFinite(hit) ? clamp(hit - frontageMargin, minSetback, defaultSetback) : defaultSetback
      inner.push([sx + nx * setback, sy + ny * setback])
    }
  }
  if (inner.length < 4) {
    throw new Error('Boundary too small to inset')
  }

  // Light simplification: drop near-collinear points (<0.3m deviation).
  const simplified: Array<readonly [number, number]> = []
  for (let i = 0; i < inner.length; i++) {
    const prev = simplified[simplified.length - 1] ?? inner[(i - 1 + inner.length) % inner.length]
    const next = inner[(i + 1) % inner.length]
    const cur = inner[i]
    const devi =
      Math.abs((next[0] - prev[0]) * (prev[1] - cur[1]) - (prev[0] - cur[0]) * (next[1] - prev[1])) /
      (Math.hypot(next[0] - prev[0], next[1] - prev[1]) || 1)
    if (devi > 0.3 || simplified.length === 0) simplified.push(cur)
  }
  const closed = [...simplified, simplified[0]]

  return {
    multiPolygon: {
      type: 'MultiPolygon',
      coordinates: [[closed.map(([x, y]) => [...toLL(x, y)])]],
    },
    stats: { samples: inner.length, simplifiedTo: simplified.length, buildingSegments: buildingSegments.length },
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
