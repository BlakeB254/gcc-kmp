/**
 * GET /api/cron/scan-watchdog — silence alarm for the scan pipeline
 * (lib/scan-index/watchdog.ts): unindexed manifests, a stale 311 mirror, or
 * reconstruction jobs nobody is claiming — plus, once each, any manifest the
 * indexer has newly rejected as permanently unusable.
 *
 * Answers 200 when healthy and 503 when any check fails, so the ms-a2 timer's
 * `curl -f` fails the `gcc-cron-scan-watchdog` unit. When CRON_ALERT_EMAIL is
 * set, a failing check also sends one email per run. Read-only, so no lease.
 *
 * Query: `scan_hours`, `civic_hours`, `recon_hours` override the thresholds
 * (defaults 6 / 48 / 24).
 */
import { NextRequest, NextResponse } from 'next/server'
import { cronAuthFailure } from '@/lib/cron-auth'
import { sendEmail } from '@/lib/email'
import { runWatchdog, thresholdsFrom, type WatchdogReport } from '@/lib/scan-index/watchdog'
import { watchdogDeps } from '@/lib/scan-index/deps'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
export const maxDuration = 30

function alertText(report: WatchdogReport): string {
  const lines = [`GCC scan pipeline watchdog — ${report.checkedAt}`, '']
  for (const [name, check] of [
    ['Scan index', report.scanIndex],
    ['Rejected manifests', report.rejectedManifests],
    ['311 mirror', report.civicSync],
    ['Reconstruction queue', report.reconQueue],
  ] as const) {
    lines.push(`${check.ok ? 'OK  ' : 'FAIL'} ${name}: ${check.detail}`)
  }
  if (report.scanIndex.examples.length > 0) lines.push('', 'Unindexed manifests:', ...report.scanIndex.examples)
  if (report.rejectedManifests.newlyRejected.length > 0) {
    lines.push('', 'Newly rejected manifests (this is the only alert for each):')
    for (const r of report.rejectedManifests.newlyRejected) lines.push(`${r.r2Key} — ${r.reason}`)
  }
  return lines.join('\n')
}

export async function GET(request: NextRequest) {
  const denied = cronAuthFailure(request)
  if (denied) return denied

  try {
    const report = await runWatchdog(watchdogDeps, thresholdsFrom(request.nextUrl.searchParams))
    console.info(JSON.stringify({ event: 'gcc.cron.scan_watchdog', ...report }))
    if (!report.healthy) {
      const to = process.env.CRON_ALERT_EMAIL?.trim()
      if (to) {
        const text = alertText(report)
        const sent = await sendEmail({
          to,
          subject: 'GCC scan pipeline: a stage has gone silent',
          text,
          html: `<pre>${text.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</pre>`,
        })
        if (!sent.success) console.error('[cron/scan-watchdog] alert email failed:', sent.error)
      }
    }
    return NextResponse.json({ success: report.healthy, ...report }, { status: report.healthy ? 200 : 503 })
  } catch (error) {
    console.error('[cron/scan-watchdog] failed:', error)
    return NextResponse.json({ success: false, error: 'scan-watchdog cron failed' }, { status: 500 })
  }
}
