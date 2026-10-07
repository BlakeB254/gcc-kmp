/**
 * GET /api/cron/route-releases  — release-queue tick.
 * Advances every due route-release batch (expire stale offers, send the next
 * wave to eligible workers in queue order), then drains the notification outbox
 * (offer email + native iOS push, including the 1h/5m expiry reminders).
 * Guarded by CRON_SECRET (`Authorization: Bearer $CRON_SECRET`); fails closed
 * when the secret is unset.
 *
 * Driven by the Render cron job `gcc-cron-route-releases` (every 15m; see
 * `render.yaml`), because Vercel Hobby caps crons at one-per-day and a
 * more-frequent `vercel.json` entry breaks all deploys. The daily `vercel.json`
 * cron is retained as a backstop — `withCronLease` makes a double-fire a no-op
 * rather than a double-issue of offers.
 *
 * Reminder precision and initial-offer latency track the Render cadence, not
 * this file.
 *
 * Also sweeps abandoned $0 test jobs (claimed and never started past the
 * start window, or in progress past 24h) back off their worker via the
 * canonical `releaseJobPatch` — see lib/stale-test-jobs.ts. Stale PAID jobs
 * are only counted in `staleJobs.paidStale`; whether to release them is an
 * open owner decision.
 */
import { NextRequest, NextResponse } from 'next/server'
import { processDueBatches } from '@/lib/route-queue'
import { dispatchDueOfferNotifications } from '@/lib/route-queue-notifications'
import { withCronLease } from '@/lib/cron-lease'
import { sweepStaleTestJobsNow } from '@/lib/stale-job-sweep'
import { logRouteReleaseMetrics, summarizeReleaseResults } from '@/lib/cron/metrics'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
// Was implicitly Vercel's ~10s default. processDueBatches() walks every due
// batch and the notification drain does up to 100 sends, so give it headroom
// (60s is the Hobby ceiling). Must stay below the lease TTL.
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120

export async function GET(request: NextRequest) {
  const secret = process.env.CRON_SECRET
  // Fail closed: without a configured secret the endpoint is disabled.
  if (!secret) {
    return NextResponse.json({ error: 'CRON_SECRET not configured', success: false }, { status: 503 })
  }
  const auth = request.headers.get('authorization')
  if (auth !== `Bearer ${secret}`) {
    return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  }
  try {
    const started = Date.now()
    const outcome = await withCronLease('route-releases', LEASE_TTL_SECONDS, async () => {
      const releases = await processDueBatches()
      // Drain after processing so this tick's freshly-scheduled "initial"
      // notifications go out in the same run.
      const notifications = await dispatchDueOfferNotifications()
      // Last, so a sweep failure can't hold up offers or notifications.
      let staleJobs
      try {
        staleJobs = await sweepStaleTestJobsNow()
        if (staleJobs.released.length > 0 || staleJobs.paidStale.length > 0) {
          console.log(
            `[cron:route-releases] stale jobs: released ${staleJobs.released.length} $0 test job(s) ` +
              `[${staleJobs.released.map((j) => j.id).join(',')}]; ` +
              `${staleJobs.paidStale.length} stale paid job(s) left in place ` +
              `[${staleJobs.paidStale.map((j) => j.id).join(',')}]`,
          )
        }
      } catch (error) {
        console.error('[cron:route-releases] stale job sweep failed:', error)
        staleJobs = { error: 'stale job sweep failed' }
      }
      return { releases, notifications, staleJobs }
    })

    // A held lease means a previous tick is still running. That is expected at a
    // 15m cadence, not an error — 200 so the scheduler doesn't alert.
    if (!outcome.ran) {
      logRouteReleaseMetrics({
        examined: 0,
        processed: 0,
        released: 0,
        durationMs: Date.now() - started,
        leaseAcquired: false,
      })
      return NextResponse.json({
        data: {
          skipped: outcome.skipped,
          ranAt: new Date().toISOString(),
          metrics: {
            examined: 0,
            processed: 0,
            released: 0,
            durationMs: Date.now() - started,
            leaseAcquired: false,
          },
        },
        success: true,
      })
    }

    const summary = summarizeReleaseResults(outcome.result.releases)
    const metrics = {
      examined: outcome.result.releases.length,
      processed: summary.processed,
      released: summary.released,
      durationMs: Date.now() - started,
      leaseAcquired: true,
    }
    logRouteReleaseMetrics(metrics)

    return NextResponse.json({
      data: { ...outcome.result, ranAt: new Date().toISOString(), metrics },
      success: true,
    })
  } catch (error) {
    console.error('GET /api/cron/route-releases error:', error)
    return NextResponse.json({ error: 'Failed to process due releases', success: false }, { status: 500 })
  }
}
