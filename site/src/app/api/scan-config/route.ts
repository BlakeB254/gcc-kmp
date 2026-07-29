import { NextResponse } from 'next/server'
import { GetObjectCommand } from '@aws-sdk/client-s3'
import { isR2Configured, r2Client, r2BucketName } from '@/lib/r2'

/**
 * Remote scan tuning (Blake 2026-07-29: error analysis must be able to
 * influence future scans without an app release). The mobile app fetches
 * this at scan open and applies SAFE knobs only — feature toggles and
 * sensor caps, never anything that could brick a session (the client
 * fail-opens to its compiled defaults on any fetch/parse problem).
 *
 * Source of truth: `config/scan-tuning.json` in the SAME R2 bucket the
 * reconstruction pipeline reads — written by a human after reviewing
 * scripts/reconstruction/analyze_scan_errors.py's proposal. Absent object =
 * compiled defaults, served explicitly so the client's cache has a
 * revision to compare.
 */
const TUNING_KEY = 'config/scan-tuning.json'

const DEFAULTS = {
  revision: 0,
  sweepEnabled: true,
  drawnMarkEnabled: true,
  sensorMaxFootprintM: 1.4,
  sensorMaxHeightM: 0.9,
  notes: 'compiled defaults (no tuning object uploaded)',
}

export async function GET() {
  if (!isR2Configured()) {
    return NextResponse.json(DEFAULTS, { headers: cacheHeaders() })
  }
  try {
    const result = await r2Client().send(
      new GetObjectCommand({ Bucket: r2BucketName(), Key: TUNING_KEY })
    )
    const body = await result.Body?.transformToString()
    if (!body) return NextResponse.json(DEFAULTS, { headers: cacheHeaders() })
    // Serve the object verbatim after a parse check — unknown extra fields
    // flow through to newer clients; older clients ignore them.
    return NextResponse.json(JSON.parse(body), { headers: cacheHeaders() })
  } catch {
    // Missing object or R2 hiccup — defaults, never an error (the scan
    // must start regardless).
    return NextResponse.json(DEFAULTS, { headers: cacheHeaders() })
  }
}

function cacheHeaders() {
  // Modest CDN cache: tuning changes are review-gated, not real-time.
  return { 'Cache-Control': 'public, max-age=300, stale-while-revalidate=600' }
}
