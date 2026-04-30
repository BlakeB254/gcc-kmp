import { NextResponse } from 'next/server'
import { ilike, or } from 'drizzle-orm'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const url = new URL(request.url)
  const q = url.searchParams.get('q')?.trim() || ''
  if (q.length < 2) return NextResponse.json({ data: [] })

  const matches = await db
    .select({
      id: sponsors.id,
      name: sponsors.name,
      email: sponsors.email,
      businessName: sponsors.businessName,
    })
    .from(sponsors)
    .where(
      or(
        ilike(sponsors.name, `%${q}%`),
        ilike(sponsors.email, `%${q}%`),
        ilike(sponsors.businessName, `%${q}%`)
      )
    )
    .limit(10)

  return NextResponse.json({ data: matches })
}
