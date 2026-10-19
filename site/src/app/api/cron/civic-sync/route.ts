/**
 * GET /api/cron/civic-sync — mirror GCC-relevant Chicago 311 requests into
 * civic_reports (lib/scan-index/civic-sync.ts).
 *
 * Replaces the Kotlin backend's 6-hourly CivicReportSync, frozen since
 * 2026-09-08. Scheduled every 6 hours by the ms-a2 user timer
 * `gcc-cron-civic-sync` (ops/systemd/ms-a2/). Resumes from the table's own
 * watermark, so a backlog drains across runs within the time budget.
 *
 * Env: CIVIC_SR_TYPES (comma-separated, default the seven cleanup types),
 * CIVIC_BACKFILL_DAYS (first-run lookback, default 90), SOCRATA_APP_TOKEN
 * (optional). Query: `backfill_days=N` overrides the lookback for an empty table.
 */
import { NextRequest, NextResponse } from 'next/server'
import { cronAuthFailure } from '@/lib/cron-auth'
import { withCronLease } from '@/lib/cron-lease'
import { runCivicSync } from '@/lib/scan-index/civic-sync'
import { civicSyncDeps } from '@/lib/scan-index/deps'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120
// Start no page after 30s; abort any request still running at 52s, leaving
// time to answer inside maxDuration.
const PAGE_BUDGET_MS = 30_000
const HARD_BUDGET_MS = 52_000

function srTypesFromEnv(): string[] | undefined {
  const types = process.env.CIVIC_SR_TYPES?.split(',').map((t) => t.trim()).filter(Boolean)
  return types && types.length > 0 ? types : undefined
}

function positive(v: string | null | undefined): number | undefined {
  const n = Number(v)
  return v != null && v !== '' && Number.isFinite(n) && n > 0 ? n : undefined
}

export async function GET(request: NextRequest) {
  const denied = cronAuthFailure(request)
  if (denied) return denied

  const start = Date.now()
  try {
    const outcome = await withCronLease('civic-sync', LEASE_TTL_SECONDS, () =>
      runCivicSync(civicSyncDeps, {
        srTypes: srTypesFromEnv(),
        backfillDays: positive(request.nextUrl.searchParams.get('backfill_days')) ?? positive(process.env.CIVIC_BACKFILL_DAYS),
        deadlineMs: start + PAGE_BUDGET_MS,
        hardDeadlineMs: start + HARD_BUDGET_MS,
      }),
    )
    if (!outcome.ran) return NextResponse.json({ success: true, skipped: outcome.skipped })
    const result = outcome.result
    console.info(JSON.stringify({ event: 'gcc.cron.civic_sync', ...result }))
    // A portal error stops the sweep (as it did in Kotlin) but must not look
    // like success to the scheduler.
    if (result.httpError) return NextResponse.json({ success: false, ...result }, { status: 502 })
    return NextResponse.json({ success: true, ...result })
  } catch (error) {
    console.error('[cron/civic-sync] failed:', error)
    return NextResponse.json({ success: false, error: 'civic-sync cron failed' }, { status: 500 })
  }
}
