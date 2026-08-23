import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { reconstructionAssets } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

const LOCATION_CELL_ID_PATTERN = /^-?\d+:-?\d+$/
const ASSET_TYPES = new Set(['pointcloud', 'splat', 'mesh'])
const FORMATS = new Set(['ply', 'glb', 'gltf', 'laz'])

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
