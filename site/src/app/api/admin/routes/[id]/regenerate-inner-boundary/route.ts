import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { eq } from 'drizzle-orm'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * Regenerates a route's inner boundary (private-property cutoff) so it
 * conforms to REAL building frontages: samples the block outline every ~2m,
 * ray-casts inward against OSM building footprints (Overpass API), and places
 * the cutoff just street-side of the first frontage hit — default parallel
 * setback where nothing is built (unfenced vacant lots stay scannable).
 *
 * Stored as a GeoJSON MultiPolygon on blocks.inner_boundary: the schema
 * supports MULTIPLE exclusion islands per route (admin-editable later per
 * the route-v2 spec); this generator emits one frontage-following island.
 */
export async function POST(request: NextRequest, context: RouteContext) {
  const admin = await requireAdmin(request)
  if (admin instanceof NextResponse) return admin

  const { id } = await context.params
  const routeId = parseInt(id, 10)
  if (Number.isNaN(routeId)) {
    return NextResponse.json({ success: false, error: 'Invalid route ID' }, { status: 400 })
  }

  const body = await request.json().catch(() => ({}))
  const defaultSetback = clamp(Number(body?.setbackMeters) || 8, 4, 20)
  const frontageMargin = 1.5
  const minSetback = 4

  const [block] = await db.select().from(blocks).where(eq(blocks.routeId, routeId)).limit(1)
  const boundary = block?.locationBoundary as { coordinates?: number[][][] } | null
  const ring = boundary?.coordinates?.[0]
  if (!block || !ring || ring.length < 4) {
    return NextResponse.json({ success: false, error: 'Route has no block boundary' }, { status: 404 })
  }

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
  let buildingSegments: Array<readonly [readonly [number, number], readonly [number, number]]> = []
  try {
    const overpass = await fetch('https://overpass-api.de/api/interpreter', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
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
  } catch (error) {
    console.error('[inner-boundary] Overpass fetch failed:', error)
    return NextResponse.json(
      { success: false, error: 'Building data source unavailable — try again shortly' },
      { status: 502 }
    )
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
      const setback = Number.isFinite(hit)
        ? clamp(hit - frontageMargin, minSetback, defaultSetback)
        : defaultSetback
      inner.push([sx + nx * setback, sy + ny * setback])
    }
  }
  if (inner.length < 4) {
    return NextResponse.json({ success: false, error: 'Boundary too small to inset' }, { status: 422 })
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
  const multiPolygon = {
    type: 'MultiPolygon',
    coordinates: [[closed.map(([x, y]) => toLL(x, y))]],
  }

  await db.update(blocks).set({ innerBoundary: multiPolygon }).where(eq(blocks.id, block.id))
  return NextResponse.json({
    success: true,
    innerBoundary: multiPolygon,
    stats: { samples: inner.length, simplifiedTo: simplified.length, buildingSegments: buildingSegments.length },
  })
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
