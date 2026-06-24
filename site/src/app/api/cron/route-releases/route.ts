/**
 * GET /api/cron/route-releases  — Vercel Cron entrypoint.
 * Advances every due route-release batch (expire stale offers, send the next
 * wave to eligible workers in queue order). Guarded by CRON_SECRET: Vercel Cron
 * automatically sends `Authorization: Bearer $CRON_SECRET` when that env var is
 * set on the project. Set CRON_SECRET in Vercel → Project → Settings → Env Vars.
 */
import { NextRequest, NextResponse } from 'next/server'
import { processDueBatches } from '@/lib/route-queue'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

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
    const releases = await processDueBatches()
    return NextResponse.json({ data: { releases, ranAt: new Date().toISOString() }, success: true })
  } catch (error) {
    console.error('GET /api/cron/route-releases error:', error)
    return NextResponse.json({ error: 'Failed to process due releases', success: false }, { status: 500 })
  }
}
