import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { db } from '@/db'
import { bounties } from '@/db/schema'
import { desc } from 'drizzle-orm'
import { bountyRowToApiBounty } from '../../bounties/_derived'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const rows = await db
    .select()
    .from(bounties)
    .orderBy(desc(bounties.updatedAt))
    .limit(100)

  return NextResponse.json({ data: rows.map(bountyRowToApiBounty), success: true, message: null })
}
