import { NextResponse } from 'next/server'
import { db } from '@/db'
import { crashReports } from '@/db/schema'
import { desc } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const rows = await db
      .select()
      .from(crashReports)
      .orderBy(desc(crashReports.createdAt))
      .limit(100)

    return NextResponse.json({ data: rows })
  } catch (error) {
    console.error('Error fetching crash reports:', error)
    return NextResponse.json({ error: 'Failed to fetch crash reports' }, { status: 500 })
  }
}
