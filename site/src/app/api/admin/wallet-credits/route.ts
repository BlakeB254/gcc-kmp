/**
 * GET /api/admin/wallet-credits — read-only.
 *
 * Ad-hoc admin credit requests awaiting a second admin (default), or every
 * request in a window with ?status=all|approved|rejected. The approve/reject
 * decisions live at /api/admin/wallet-credits/[id]/{approve,reject}.
 *
 * Query: status (pending | approved | rejected | all; default pending),
 *        days (window, default 30, max 365).
 */
import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { adminCreditLimits } from '@/lib/admin-credit'
import { listPendingAdminCredits } from '@/lib/wallet-reconcile'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const STATUSES = new Set(['pending', 'approved', 'rejected', 'all'])

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const url = new URL(request.url)
  const status = url.searchParams.get('status') ?? 'pending'
  if (!STATUSES.has(status)) {
    return NextResponse.json({ error: 'status must be pending, approved, rejected or all' }, { status: 400 })
  }
  const daysRaw = parseInt(url.searchParams.get('days') ?? '30', 10)
  const days = Number.isInteger(daysRaw) && daysRaw > 0 ? Math.min(daysRaw, 365) : 30
  const since = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString()

  try {
    const all = await listPendingAdminCredits({ since, limit: 500 })
    const requests = status === 'all' ? all : all.filter((r) => r.status === status)
    return NextResponse.json({
      data: { requests, count: requests.length, since, limits: adminCreditLimits() },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('GET /api/admin/wallet-credits error:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to list credit requests' },
      { status: 500 }
    )
  }
}
