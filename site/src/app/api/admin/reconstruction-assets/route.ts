import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { reconstructionAssets } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { and, asc, desc, eq, isNotNull, isNull, type SQL } from 'drizzle-orm'

const LOCATION_CELL_ID_PATTERN = /^-?\d+:-?\d+$/
const ASSET_TYPES = new Set(['pointcloud', 'splat', 'mesh'])
const FORMATS = new Set(['ply', 'glb', 'gltf', 'laz'])

const DEFAULT_LIMIT = 100
const MAX_LIMIT = 500

/**
 * Read surface for reconstruction assets — the minimal slice of the
 * world-model layer design (docs/superpowers/specs/2026-07-10-world-model-
 * layer-design.md, still DESIGN ONLY: the full /api/world-model/* surface
 * stays unbuilt pending review). Exists so cdx-world-twin's gcc_assets.py
 * can gain its reserved URL mode instead of reaching into Neon directly;
 * the field names below are exactly what that module consumes (`activated`
 * derives from `activatedAt IS NOT NULL` on the reading side).
 *
 * Filters (all optional): `locationCellId`, `assetType`
 * (pointcloud|splat|mesh), `activated` (true|false), `limit` (<= 500).
 * Ordered by (locationCellId, assetType, version DESC), so the first row
 * per (cell, type) pair is the current version — the same DISTINCT ON
 * ordering the twin's direct-DB query uses.
 */
export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)

    const filters: SQL[] = []

    const locationCellId = searchParams.get('locationCellId')
    if (locationCellId != null) {
      if (!LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
        return NextResponse.json({ data: null, success: false, message: 'Invalid locationCellId' }, { status: 400 })
      }
      filters.push(eq(reconstructionAssets.locationCellId, locationCellId))
    }

    const assetType = searchParams.get('assetType')
    if (assetType != null) {
      if (!ASSET_TYPES.has(assetType)) {
        return NextResponse.json({ data: null, success: false, message: `assetType must be one of: ${[...ASSET_TYPES].join(', ')}` }, { status: 400 })
      }
      filters.push(eq(reconstructionAssets.assetType, assetType))
    }

    const activated = searchParams.get('activated')
    if (activated != null) {
      if (activated !== 'true' && activated !== 'false') {
        return NextResponse.json({ data: null, success: false, message: 'activated must be true or false' }, { status: 400 })
      }
      filters.push(activated === 'true'
        ? isNotNull(reconstructionAssets.activatedAt)
        : isNull(reconstructionAssets.activatedAt))
    }

    const limitParam = searchParams.get('limit')
    const limit = limitParam != null ? Number(limitParam) : DEFAULT_LIMIT
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
      return NextResponse.json({ data: null, success: false, message: `limit must be an integer between 1 and ${MAX_LIMIT}` }, { status: 400 })
    }

    const rows = await db
      .select()
      .from(reconstructionAssets)
      .where(filters.length > 0 ? and(...filters) : undefined)
      .orderBy(
        asc(reconstructionAssets.locationCellId),
        asc(reconstructionAssets.assetType),
        desc(reconstructionAssets.version)
      )
      .limit(limit)

    return NextResponse.json({ data: rows, success: true })
  } catch (error) {
    console.error('Admin list reconstruction assets error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to list reconstruction assets' }, { status: 500 })
  }
}

/**
 * Registers a reconstructed 3D asset (DGX-side conversion output) against
 * the location cell it was built for. The DGX conversion tooling
 * (scripts/convert-scan-asset.py) uploads the file to R2 itself via boto3,
 * then calls this route to record the row — kept on the TypeScript side so
 * the DB write goes through Drizzle rather than duplicating a Postgres
 * driver in the Python tooling.
 */
export async function POST(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json()
    const { locationCellId, assetType, format, r2Key, version, sourceScanCoverageEventIds } = body

    if (typeof locationCellId !== 'string' || !LOCATION_CELL_ID_PATTERN.test(locationCellId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid locationCellId' }, { status: 400 })
    }
    if (typeof assetType !== 'string' || !ASSET_TYPES.has(assetType)) {
      return NextResponse.json({ data: null, success: false, message: `assetType must be one of: ${[...ASSET_TYPES].join(', ')}` }, { status: 400 })
    }
    if (typeof format !== 'string' || !FORMATS.has(format)) {
      return NextResponse.json({ data: null, success: false, message: `format must be one of: ${[...FORMATS].join(', ')}` }, { status: 400 })
    }
    if (typeof r2Key !== 'string' || !r2Key.startsWith(`reconstructed/${locationCellId}/`)) {
      return NextResponse.json({ data: null, success: false, message: 'r2Key must be under reconstructed/{locationCellId}/' }, { status: 400 })
    }
    if (!Number.isInteger(version) || version < 1) {
      return NextResponse.json({ data: null, success: false, message: 'version must be a positive integer' }, { status: 400 })
    }
    if (!Array.isArray(sourceScanCoverageEventIds) || !sourceScanCoverageEventIds.every((n) => Number.isInteger(n))) {
      return NextResponse.json({ data: null, success: false, message: 'sourceScanCoverageEventIds must be an array of integers' }, { status: 400 })
    }

    const [created] = await db
      .insert(reconstructionAssets)
      .values({ locationCellId, assetType, format, r2Key, version, sourceScanCoverageEventIds })
      .returning()

    return NextResponse.json({ data: created, success: true })
  } catch (error) {
    console.error('Admin create reconstruction asset error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to register reconstruction asset' }, { status: 500 })
  }
}
