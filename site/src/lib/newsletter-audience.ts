// Resolves the deduplicated set of email addresses that should receive the
// newsletter. Audience = sponsors with preferences_wants_updates != false +
// all waitlist entries, minus anyone in email_unsubscribes. Sponsors win the
// dedup tiebreak so we can show the better display name in the admin UI.

import { db } from '@/db'
import {
  sponsors,
  waitlistEntries,
  emailUnsubscribes,
} from '@/db/schema'

export interface AudienceMember {
  email: string
  name: string | null
  source: 'sponsor' | 'waitlist'
}

const TEST_EMAIL_PATTERNS = [/^test@/i, /@example\./i, /^noreply@/i]

function isJunkEmail(e: string): boolean {
  if (!e || !e.includes('@') || !e.includes('.')) return true
  return TEST_EMAIL_PATTERNS.some((re) => re.test(e))
}

export async function getNewsletterAudience(): Promise<AudienceMember[]> {
  const [sponsorRows, waitlistRows, unsubRows] = await Promise.all([
    db
      .select({
        email: sponsors.email,
        name: sponsors.name,
        businessName: sponsors.businessName,
        wantsUpdates: sponsors.preferencesWantsUpdates,
      })
      .from(sponsors),
    db
      .select({
        email: waitlistEntries.email,
        name: waitlistEntries.name,
      })
      .from(waitlistEntries),
    db.select({ email: emailUnsubscribes.email }).from(emailUnsubscribes),
  ])

  const unsubSet = new Set(unsubRows.map((r) => r.email.toLowerCase().trim()))
  const seen = new Map<string, AudienceMember>()

  // Sponsors first so their display names win the dedup
  for (const r of sponsorRows) {
    if (r.wantsUpdates === false) continue
    const e = (r.email || '').toLowerCase().trim()
    if (!e || isJunkEmail(e) || unsubSet.has(e)) continue
    if (!seen.has(e)) {
      seen.set(e, {
        email: e,
        name: r.businessName?.trim() || r.name?.trim() || null,
        source: 'sponsor',
      })
    }
  }
  for (const r of waitlistRows) {
    const e = (r.email || '').toLowerCase().trim()
    if (!e || isJunkEmail(e) || unsubSet.has(e)) continue
    if (!seen.has(e)) {
      seen.set(e, {
        email: e,
        name: r.name?.trim() || null,
        source: 'waitlist',
      })
    }
  }

  return [...seen.values()].sort((a, b) => a.email.localeCompare(b.email))
}
