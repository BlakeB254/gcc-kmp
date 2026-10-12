/**
 * GET /api/admin/wallets/reconcile — read-only.
 *
 * Lists every worker whose wallet balance column differs from the sum of
 * their wallet ledger entries. Such a worker cannot cash out (the withdraw
 * route refuses with "Your balance is being reviewed") until an admin fixes
 * the difference.
 *
 * Also lists, as their own ledger lines, every ad-hoc admin credit in the
 * window (`adminCredits`: who credited whom, how much, under which key) and
 * every second-approval request in any state (`pendingAdminCredits`), with
 * the limits in force, so manual money can be reviewed in one place.
 *
 * Query: days (window for the admin-credit lines, default 30, max 365).
 */
import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { adminCreditLimits } from '@/lib/admin-credit'
import { findWalletMismatches, listAdminCredits, listPendingAdminCredits } from '@/lib/wallet-reconcile'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const url = new URL(request.url)
  const daysRaw = parseInt(url.searchParams.get('days') ?? '30', 10)
  const days = Number.isInteger(daysRaw) && daysRaw > 0 ? Math.min(daysRaw, 365) : 30
  const since = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString()

  try {
    const [mismatches, adminCredits, pendingAdminCredits] = await Promise.all([
      findWalletMismatches(),
      listAdminCredits({ since, limit: 500 }),
      listPendingAdminCredits({ since, limit: 500 }),
    ])
    return NextResponse.json({
      data: {
        mismatches,
        count: mismatches.length,
        adminCredits,
        pendingAdminCredits,
        adminCreditWindow: { since, limit: 500 },
        adminCreditLimits: adminCreditLimits(),
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('GET /api/admin/wallets/reconcile error:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Failed to reconcile wallets' },
      { status: 500 }
    )
  }
}
