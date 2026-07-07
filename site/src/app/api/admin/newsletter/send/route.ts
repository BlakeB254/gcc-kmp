import { NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { newsletterSends } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { sendEmail, EMAIL_FROM, EMAIL_REPLY_TO } from '@/lib/email'
import { getNewsletterAudience, type AudienceMember } from '@/lib/newsletter-audience'
import { newsletterIssue1Email } from '@/lib/email/templates/newsletter-issue-1'
import { unsubscribeUrl } from '@/lib/unsubscribe-token'
import { appUrl as resolveAppUrl } from '@/lib/env'

export const runtime = 'nodejs'
export const maxDuration = 300 // 5 min — Resend sends are sequential

const ISSUE_SLUG = '2026-05-01-issue-01'

interface SendBody {
  mode: 'test' | 'production'
  testRecipient?: string  // Required when mode === 'test'
}

export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const body = (await request.json().catch(() => ({}))) as SendBody
  const mode = body.mode === 'test' ? 'test' : 'production'

  const appUrl = resolveAppUrl()
  const logoUrl = `${appUrl}/images/gcc-logo.png`

  let audience: AudienceMember[]
  if (mode === 'test') {
    if (!body.testRecipient || !body.testRecipient.includes('@')) {
      return NextResponse.json({ error: 'testRecipient email required for test mode' }, { status: 400 })
    }
    audience = [{ email: body.testRecipient.toLowerCase().trim(), name: null, source: 'sponsor' }]
  } else {
    audience = await getNewsletterAudience()
    if (audience.length === 0) {
      return NextResponse.json({ error: 'Audience is empty' }, { status: 400 })
    }
    // Idempotency: refuse to re-send the same issue twice in production mode.
    const existing = await db
      .select()
      .from(newsletterSends)
      .where(eq(newsletterSends.issueSlug, ISSUE_SLUG))
      .limit(1)
    if (existing.length > 0) {
      return NextResponse.json(
        {
          error: `Issue ${ISSUE_SLUG} was already sent at ${existing[0].startedAt}.`,
          existingSendId: existing[0].id,
        },
        { status: 409 }
      )
    }
  }

  // Insert send record up-front so we can update with results later.
  const [sendRecord] = mode === 'production'
    ? await db
        .insert(newsletterSends)
        .values({
          issueSlug: ISSUE_SLUG,
          subject: '(pending)',
          recipientCount: audience.length,
          successCount: 0,
          failureCount: 0,
          triggeredByWorkerId: auth.workerId,
        })
        .returning({ id: newsletterSends.id })
    : [null]

  let success = 0
  let failure = 0
  const failed: Array<{ email: string; error: string }> = []
  let firstSubject = ''

  // Send sequentially — Resend's free tier is 2 req/sec; sponsor list is small;
  // we don't need batching gymnastics for an audience of ~25.
  for (const member of audience) {
    const { html, text, subject } = newsletterIssue1Email({
      recipientName: member.name,
      unsubscribeUrl: unsubscribeUrl(member.email, appUrl),
      appUrl,
      logoUrl,
    })
    if (!firstSubject) firstSubject = subject

    const result = await sendEmail({
      to: member.email,
      subject,
      html,
      text,
      replyTo: EMAIL_REPLY_TO,
    })
    if (result.success) {
      success++
    } else {
      failure++
      failed.push({ email: member.email, error: result.error || 'unknown' })
    }
    // Light rate-limit cushion (~3 emails/sec)
    await new Promise((r) => setTimeout(r, 350))
  }

  if (sendRecord) {
    await db
      .update(newsletterSends)
      .set({
        subject: firstSubject,
        successCount: success,
        failureCount: failure,
        failedRecipients: failed,
        completedAt: new Date().toISOString(),
      })
      .where(eq(newsletterSends.id, sendRecord.id))
  }

  return NextResponse.json({
    data: {
      mode,
      sendId: sendRecord?.id ?? null,
      from: EMAIL_FROM,
      subject: firstSubject,
      attempted: audience.length,
      success,
      failure,
      failed,
    },
  })
}

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const sends = await db.select().from(newsletterSends).orderBy(newsletterSends.startedAt)
  return NextResponse.json({ data: sends })
}
