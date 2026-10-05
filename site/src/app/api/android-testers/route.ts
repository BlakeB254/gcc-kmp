import { after, NextRequest, NextResponse } from 'next/server'
import { z } from 'zod'
import { db } from '@/db'
import { androidTesterSignups } from '@/db/schema'
import { sendEmail } from '@/lib/email'
import { androidTesterSignupEmail } from '@/lib/email/templates/android-tester'
import { clientIp, isRateLimited } from '@/lib/rate-limit'
import { normalizeTesterEmail, normalizeTesterName, testerGroupUrl } from '@/lib/android-testers'

export const runtime = 'nodejs'

// Public signup for the Android closed test (/android-beta). Unauthenticated
// on purpose — see PUBLIC_ROUTES in app/api/route-guards.test.ts. It stores an
// address for an admin to add to the Play Console tester list; it grants
// nothing by itself.

const signupSchema = z.object({
  email: z.string().max(254),
  name: z.string().max(200).optional(),
  source: z.string().trim().max(64).optional(),
  // Honeypot: hidden from people, filled in by form-stuffing bots.
  website: z.string().optional(),
})

export async function POST(request: NextRequest) {
  try {
    if (await isRateLimited(`android-testers:${clientIp(request)}`, 5, 600)) {
      return NextResponse.json(
        { success: false, error: 'Too many attempts. Please try again in a few minutes.' },
        { status: 429 },
      )
    }

    const parsed = signupSchema.safeParse(await request.json().catch(() => null))
    const email = parsed.success ? normalizeTesterEmail(parsed.data.email) : null
    if (!parsed.success || !email) {
      return NextResponse.json(
        { success: false, error: 'Please enter a valid email address.' },
        { status: 400 },
      )
    }

    const groupUrl = testerGroupUrl()
    const ok = NextResponse.json({ success: true, selfServe: groupUrl !== null })

    // A filled honeypot gets the same answer as a real signup and nothing else.
    if (parsed.data.website) return ok

    const name = normalizeTesterName(parsed.data.name)
    const inserted = await db
      .insert(androidTesterSignups)
      .values({ email, name, source: parsed.data.source || 'android-beta' })
      .onConflictDoNothing({ target: androidTesterSignups.email })
      .returning({ id: androidTesterSignups.id })

    // Signing up twice is fine and looks the same from outside, so the form
    // cannot be used to find out whether an address is already on the list.
    // Only a first signup sends mail, so it cannot be used to spam one either.
    // The mail goes out after the response: waiting for it here would make a
    // first signup measurably slower than a repeat, which gives the answer away.
    if (inserted.length > 0) {
      after(async () => {
        const content = androidTesterSignupEmail({ groupUrl })
        const sent = await sendEmail({
          to: email,
          subject: groupUrl
            ? 'Join the GCC Android test — 3 short steps'
            : 'You are signed up to test GCC on Android',
          html: content.html,
          text: content.text,
        })
        if (!sent.success) {
          console.error('[android-testers] confirmation email failed:', sent.error)
        }
      })
    }

    return ok
  } catch (error) {
    console.error('[android-testers] signup error:', error)
    return NextResponse.json(
      { success: false, error: 'Could not sign you up. Please try again.' },
      { status: 500 },
    )
  }
}
