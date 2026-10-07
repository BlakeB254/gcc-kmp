/**
 * GET /api/admin/wallets/reconcile — read-only.
 *
 * Lists every worker whose wallet balance column differs from the sum of
 * their wallet ledger entries. Such a worker cannot cash out (the withdraw
 * route refuses with "Your balance is being reviewed") until an admin fixes
 * the difference.
 */
import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { findWalletMismatches } from '@/lib/wallet-reconcile'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const mismatches = await findWalletMismatches()
    return NextResponse.json({
      data: { mismatches, count: mismatches.length },
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
