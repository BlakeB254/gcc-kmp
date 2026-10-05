import { NextResponse } from 'next/server'
import { desc, eq, inArray } from 'drizzle-orm'
import { z } from 'zod'
import { db } from '@/db'
import { androidTesterSignups } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { sendEmail } from '@/lib/email'
import { androidTesterInviteEmail } from '@/lib/email/templates/android-tester'
import {
  TESTER_ACTIONS,
  nextTesterState,
  playConsoleEmailList,
  testerProgress,
  type TesterStatus,
} from '@/lib/android-testers'

export const runtime = 'nodejs'

// Admin side of the Android closed-test flow (/admin/android-testers).

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const rows = await db
    .select()
    .from(androidTesterSignups)
    .orderBy(desc(androidTesterSignups.createdAt))
  const typed = rows.map((r) => ({ ...r, status: r.status as TesterStatus }))

  return NextResponse.json({
    data: typed,
    progress: testerProgress(typed),
    playConsoleEmails: playConsoleEmailList(typed),
  })
}

const actionSchema = z.object({
  action: z.enum(TESTER_ACTIONS),
  ids: z.array(z.number().int().positive()).min(1).max(200),
})

/**
 * Apply one action to a set of signups.
 *
 * `invite` is the one with a side effect: it marks the signup as on the Play
 * Console tester list and emails the opt-in link. Run it only AFTER pasting
 * the address into Play Console — the link shows "not available" to an
 * account that is not on the list. A failed email leaves the row `added`
 * with no inviteSentAt, so it shows up as needing a re-send.
 */
export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const parsed = actionSchema.safeParse(await request.json().catch(() => null))
  if (!parsed.success) {
    return NextResponse.json({ error: 'Invalid action or ids' }, { status: 400 })
  }
  const { action, ids } = parsed.data

  const rows = await db.select().from(androidTesterSignups).where(inArray(androidTesterSignups.id, ids))
  const failedEmails: string[] = []

  for (const row of rows) {
    const now = new Date().toISOString()
    const next = nextTesterState(
      { status: row.status as TesterStatus, addedAt: row.addedAt, joinedAt: row.joinedAt },
      action,
      now,
    )

    let inviteSentAt = row.inviteSentAt
    if (action === 'invite' && next.status !== 'removed') {
      const content = androidTesterInviteEmail()
      const sent = await sendEmail({
        to: row.email,
        subject: 'Your GCC Android test invite is ready',
        html: content.html,
        text: content.text,
      })
      if (sent.success) inviteSentAt = now
      else failedEmails.push(row.email)
    }

    await db
      .update(androidTesterSignups)
      .set({ ...next, inviteSentAt, updatedAt: now })
      .where(eq(androidTesterSignups.id, row.id))
  }

  return NextResponse.json({ updated: rows.length, failedEmails })
}
