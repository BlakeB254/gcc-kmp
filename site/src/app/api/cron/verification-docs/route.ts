/**
 * GET /api/cron/verification-docs — PII hygiene for verification documents.
 *
 * Two jobs, each opt-in per call so the scheduler (ms-a2 user timer
 * `gcc-cron-verification-docs`, see docs/governance/cron-and-scheduling.md)
 * owns the policy and a bare call changes nothing:
 *
 *   ?migrate=dry|apply&limit=N   move legacy PUBLIC Blob documents into
 *                                private R2 (lib/legacy-verification-docs.ts).
 *                                Default dry. limit defaults to 20 per run.
 *   ?retention_days=N            purge private verification/ objects older
 *   &retention=dry|enforce       than N days (≥30) whose worker is not in
 *                                review (lib/verification-retention.ts).
 *                                Skipped unless retention_days is given;
 *                                default dry.
 *
 * Guarded by CRON_SECRET; refuses (503) when R2 is not configured. The
 * response carries the migration manifest (blob path → R2 key, size, MD5) so
 * the caller keeps a record of every move.
 */
import { NextRequest, NextResponse } from 'next/server'
import { cronAuthFailure } from '@/lib/cron-auth'
import { withCronLease } from '@/lib/cron-lease'
import { isR2Configured } from '@/lib/r2'
import { migrateLegacyDocs } from '@/lib/legacy-verification-docs'
import { sweepVerificationDocs } from '@/lib/verification-retention'
import { migrationDeps, retentionDeps } from '@/lib/verification-docs-deps'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120
const DEFAULT_LIMIT = 20
const MAX_LIMIT = 50

export async function GET(request: NextRequest) {
  const denied = cronAuthFailure(request)
  if (denied) return denied
  if (!isR2Configured()) {
    return NextResponse.json({ success: false, error: 'R2 not configured' }, { status: 503 })
  }

  const q = request.nextUrl.searchParams
  const migrate = q.get('migrate')
  const limit = Math.min(MAX_LIMIT, Math.max(1, Number(q.get('limit')) || DEFAULT_LIMIT))
  const retentionDays = q.has('retention_days') ? Number(q.get('retention_days')) : null

  try {
    const outcome = await withCronLease('verification-docs', LEASE_TTL_SECONDS, async () => {
      const migration = migrate
        ? await migrateLegacyDocs(migrationDeps, { apply: migrate === 'apply', limit })
        : null
      const retention = retentionDays != null
        ? await sweepVerificationDocs(retentionDeps, { retentionDays, enforce: q.get('retention') === 'enforce' })
        : null
      return { migration, retention }
    })
    if (!outcome.ran) return NextResponse.json({ success: true, skipped: outcome.skipped })
    const { migration, retention } = outcome.result
    console.log('[cron/verification-docs]', JSON.stringify({
      migration: migration && { ...migration, manifest: undefined },
      retention,
    }))
    return NextResponse.json({ success: true, migration, retention })
  } catch (error) {
    console.error('[cron/verification-docs] failed:', error)
    return NextResponse.json({ success: false, error: 'verification-docs cron failed' }, { status: 500 })
  }
}
