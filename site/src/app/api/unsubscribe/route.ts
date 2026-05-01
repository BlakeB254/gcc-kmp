import { NextResponse } from 'next/server'
import { sql } from 'drizzle-orm'
import { db } from '@/db'
import { emailUnsubscribes } from '@/db/schema'
import { verifyUnsubscribeToken } from '@/lib/unsubscribe-token'

export const runtime = 'nodejs'

// POST /api/unsubscribe
// Body: { token: string, reason?: string }
// Verifies the HMAC token, then upserts an email_unsubscribes row.
export async function POST(request: Request) {
  const body = (await request.json().catch(() => ({}))) as {
    token?: string
    reason?: string
  }
  if (!body.token) {
    return NextResponse.json({ error: 'token required' }, { status: 400 })
  }

  const result = verifyUnsubscribeToken(body.token)
  if (!result.valid) {
    return NextResponse.json({ error: result.reason }, { status: 400 })
  }

  const email = result.email.toLowerCase().trim()
  const reason = body.reason?.trim()?.slice(0, 500) || null

  // Upsert: if already unsubscribed, do nothing
  await db
    .insert(emailUnsubscribes)
    .values({
      email,
      reason,
      unsubscribedFrom: 'newsletter',
    })
    .onConflictDoNothing({ target: emailUnsubscribes.email })

  // Best-effort: also flip the sponsor flag if a sponsor record matches
  await db.execute(sql`
    UPDATE sponsors SET preferences_wants_updates = FALSE
     WHERE lower(email) = ${email}
  `)

  return NextResponse.json({ data: { email, status: 'unsubscribed' } })
}
