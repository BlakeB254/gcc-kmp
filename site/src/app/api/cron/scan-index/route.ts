/**
 * GET /api/cron/scan-index — mirror R2 capture sessions into scan_sessions /
 * capture_chunks / reconstruction_assets and enqueue reconstruction_jobs for
 * the Spark GPU worker (lib/scan-index/indexer.ts).
 *
 * This replaces the Kotlin backend's 30-minute in-process sweep, which has run
 * on no host since 2026-09-08; until this route is scheduled, no new scan is
 * indexed and nothing reaches the reconstruction queue. Scheduled every 30
 * minutes by the ms-a2 user timer `gcc-cron-scan-index`
 * (ops/systemd/ms-a2/, docs/governance/cron-and-scheduling.md).
 *
 * Each run handles only manifests whose session changed since it was last
 * indexed, within a time budget; the rest is picked up next run. Leased so two
 * ticks never index the same backlog at once.
 *
 * Query: `full=1` re-indexes every manifest (budget permitting); `limit=N`
 * caps manifests per run.
 */
import { NextRequest, NextResponse } from 'next/server'
import { cronAuthFailure } from '@/lib/cron-auth'
import { withCronLease } from '@/lib/cron-lease'
import { isR2Configured } from '@/lib/r2'
import { runScanIndex } from '@/lib/scan-index/indexer'
import { scanIndexDeps } from '@/lib/scan-index/deps'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120
// Stop starting manifests here; the reconstruction pass and enqueue still
// have to fit inside maxDuration.
const MANIFEST_BUDGET_MS = 40_000

export async function GET(request: NextRequest) {
  const denied = cronAuthFailure(request)
  if (denied) return denied
  if (!isR2Configured()) {
    return NextResponse.json({ success: false, error: 'R2 not configured' }, { status: 503 })
  }

  const q = request.nextUrl.searchParams
  const limit = Number(q.get('limit'))
  try {
    const outcome = await withCronLease('scan-index', LEASE_TTL_SECONDS, () =>
      runScanIndex(scanIndexDeps, {
        deadlineMs: Date.now() + MANIFEST_BUDGET_MS,
        maxManifests: Number.isInteger(limit) && limit > 0 ? limit : undefined,
        full: q.get('full') === '1',
      }),
    )
    if (!outcome.ran) return NextResponse.json({ success: true, skipped: outcome.skipped })
    console.info(JSON.stringify({ event: 'gcc.cron.scan_index', ...outcome.result }))
    return NextResponse.json({ success: true, ...outcome.result })
  } catch (error) {
    console.error('[cron/scan-index] failed:', error)
    return NextResponse.json({ success: false, error: 'scan-index cron failed' }, { status: 500 })
  }
}
