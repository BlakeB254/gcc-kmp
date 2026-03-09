import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  // No bounty system implemented yet — return empty list
  // Bounties are separate from spot reports; they require monetary pledges
  return NextResponse.json({ data: [] })
}
