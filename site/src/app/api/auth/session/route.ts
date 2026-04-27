/**
 * GET /api/auth/session
 *
 * Returns the current session's full picture: which roles the user has
 * (worker, sponsor, admin), plus a slim profile + summary stats from each
 * side they're authenticated for. Powers the unified /member portal so it
 * can render every role's section in one render with no waterfall.
 *
 * - Reads worker-token + sponsor-token cookies independently.
 * - Admin role is inferred from ADMIN_EMAILS, applied to whichever email
 *   is on file (worker takes precedence; falls back to sponsor).
 * - Returns 401 only when neither cookie produces an authenticated user.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'

export const runtime = 'nodejs'

const ADMIN_EMAILS = (process.env.ADMIN_EMAILS || '')
  .split(',')
  .map((e) => e.trim().toLowerCase())
  .filter(Boolean)

interface WorkerSlice {
  id: number
  name: string
  email: string
  emailVerified: boolean
  verificationStatus: string
  documents: {
    governmentIdUploaded: boolean
    governmentIdStatus: string
    proofOfAddressUploaded: boolean
    proofOfAddressStatus: string
  }
  stats: { jobsCompleted: number; totalEarnings: number }
}

interface SponsorSlice {
  id: number
  name: string
  email: string
  type: string
  stats: {
    totalContributed: number
    blocksFunded: number
    lastContributionAt: string | null
  }
  recentContributions: Array<{
    id: number
    amount: number
    status: string
    createdAt: string
    blockName: string | null
  }>
}

async function loadWorker(request: NextRequest): Promise<WorkerSlice | null> {
  const token = request.cookies.get('worker-token')?.value
  if (!token) return null
  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') return null

  const [w] = await db.select().from(workers).where(eq(workers.id, payload.id)).limit(1)
  if (!w) return null
  return {
    id: w.id,
    name: w.name,
    email: w.email,
    emailVerified: !!w.emailVerified,
    verificationStatus: w.verificationStatus || 'pending',
    documents: {
      governmentIdUploaded: w.verificationGovernmentIdId !== null,
      governmentIdStatus: w.verificationGovernmentIdStatus || 'pending',
      proofOfAddressUploaded: w.verificationProofOfAddressId !== null,
      proofOfAddressStatus: w.verificationProofOfAddressStatus || 'pending',
    },
    stats: {
      jobsCompleted: Number(w.statsJobsCompleted) || 0,
      totalEarnings: Number(w.statsTotalEarnings) || 0,
    },
  }
}

async function loadSponsor(request: NextRequest): Promise<SponsorSlice | null> {
  const token = request.cookies.get('sponsor-token')?.value
  if (!token) return null
  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'sponsor') return null

  const sponsorRows = await db.execute(sql`
    SELECT id, name, email, type,
           COALESCE(stats_total_contributed, 0) AS total_contributed,
           COALESCE(stats_blocks_funded, 0) AS blocks_funded,
           stats_last_contribution_at
    FROM sponsors WHERE id = ${payload.id} LIMIT 1
  `)
  const s = (sponsorRows.rows as Array<Record<string, unknown>>)[0]
  if (!s) return null

  // Most recent 5 transactions w/ block name
  const txRows = await db.execute(sql`
    SELECT t.id, t.amount, t.status, t.created_at, b.name AS block_name
    FROM transactions t
    LEFT JOIN blocks b ON b.id = t.block_id
    WHERE t.sponsor_id = ${payload.id}
    ORDER BY t.created_at DESC
    LIMIT 5
  `)

  return {
    id: s.id as number,
    name: s.name as string,
    email: s.email as string,
    type: s.type as string,
    stats: {
      totalContributed: Number(s.total_contributed) || 0,
      blocksFunded: Number(s.blocks_funded) || 0,
      lastContributionAt: (s.stats_last_contribution_at as string) || null,
    },
    recentContributions: (txRows.rows as Array<Record<string, unknown>>).map((r) => ({
      id: r.id as number,
      amount: Number(r.amount) || 0,
      status: (r.status as string) || 'unknown',
      createdAt: (r.created_at as Date)?.toISOString?.() || (r.created_at as string),
      blockName: (r.block_name as string) || null,
    })),
  }
}

export async function GET(request: NextRequest) {
  const [worker, sponsor] = await Promise.all([loadWorker(request), loadSponsor(request)])

  if (!worker && !sponsor) {
    return NextResponse.json({ authenticated: false }, { status: 401 })
  }

  const email = (worker?.email || sponsor?.email || '').toLowerCase()
  const isAdmin = ADMIN_EMAILS.includes(email)

  const roles: Array<'worker' | 'sponsor' | 'admin'> = []
  if (worker) roles.push('worker')
  if (sponsor) roles.push('sponsor')
  if (isAdmin) roles.push('admin')

  return NextResponse.json({
    authenticated: true,
    email,
    name: worker?.name || sponsor?.name || '',
    roles,
    worker,
    sponsor,
  })
}
