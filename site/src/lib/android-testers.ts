/**
 * Rules for the Android closed-test signup flow.
 *
 * Google Play will not let a personal developer account publish to production
 * (or open testing) until a closed test has had at least 12 opted-in testers
 * for 14 days in a row. A tester only counts once two things are true: their
 * Google account is on the track's tester list, and they have tapped "Become
 * a tester" on the opt-in link. The site collects the address
 * (/android-beta -> POST /api/android-testers); an admin adds it to the list
 * in Play Console and sends the opt-in link (/admin/android-testers).
 *
 * Everything here is pure — no `@/db` import — so it runs under `tsx --test`
 * without a database.
 *
 * When Android is public on Google Play, point the site's Android buttons at
 * ANDROID_PLAY_STORE_URL instead of ANDROID_BETA_PATH and retire the page.
 */

export const ANDROID_PACKAGE = 'com.globalcommunitycleaners.worker'
/** Closed-test join page. Shows "not available" to accounts not on the tester list. */
export const ANDROID_TESTING_OPT_IN_URL = `https://play.google.com/apps/testing/${ANDROID_PACKAGE}`
/** Store listing. 404s for everyone who has not opted in to the closed test. */
export const ANDROID_PLAY_STORE_URL = `https://play.google.com/store/apps/details?id=${ANDROID_PACKAGE}`
export const ANDROID_BETA_PATH = '/android-beta'

/** Google's minimum number of opted-in testers. */
export const TESTERS_REQUIRED = 12
/** Days the testers must stay opted in, consecutively. */
export const TEST_DAYS = 14

export const TESTER_STATUSES = ['new', 'added', 'joined', 'removed'] as const
/**
 * new     — signed up on the site, not yet on the Play Console tester list
 * added   — on the Play Console list (and, if inviteSentAt is set, emailed the link)
 * joined  — confirmed they tapped "Become a tester"
 * removed — dropped out or bad address; excluded from counts and from the paste list
 */
export type TesterStatus = (typeof TESTER_STATUSES)[number]

export const TESTER_ACTIONS = ['invite', 'joined', 'remove', 'restore'] as const
export type TesterAction = (typeof TESTER_ACTIONS)[number]

// Deliberately stricter than RFC 5322: one address, no spaces or commas, a
// dotted domain. Play Console's tester box splits on commas, so an address
// that smuggles one in would add a stranger to the list.
const EMAIL_RE = /^[^\s@,;<>()"]+@[^\s@,;<>()"]+\.[^\s@,;<>()".]{2,}$/

/** Canonical form of a tester's Google account address, or null if unusable. */
export function normalizeTesterEmail(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const email = raw.trim().toLowerCase()
  if (email.length > 254 || !EMAIL_RE.test(email)) return null
  return email
}

/**
 * The self-join Google Group link, when one is configured. With a group
 * attached to the closed-test track, a visitor can join it and opt in at once
 * instead of waiting for an admin. Only a real groups.google.com link is ever
 * returned, so a mistyped env value cannot send visitors somewhere else.
 */
export function testerGroupUrl(
  value: string | undefined = process.env.NEXT_PUBLIC_ANDROID_TESTER_GROUP_URL,
): string | null {
  const trimmed = value?.trim()
  if (!trimmed) return null
  try {
    const url = new URL(trimmed)
    if (url.protocol !== 'https:' || url.hostname !== 'groups.google.com') return null
    return url.toString()
  } catch {
    return null
  }
}

export interface TesterState {
  status: TesterStatus
  addedAt: string | null
  joinedAt: string | null
}

/** What an admin action does to a signup. `now` is an ISO timestamp. */
export function nextTesterState(current: TesterState, action: TesterAction, now: string): TesterState {
  switch (action) {
    case 'invite':
      // Re-sending the link to someone already on the list changes nothing.
      if (current.status !== 'new') return current
      return { ...current, status: 'added', addedAt: now }
    case 'joined':
      return { status: 'joined', addedAt: current.addedAt ?? now, joinedAt: current.joinedAt ?? now }
    case 'remove':
      return { ...current, status: 'removed' }
    case 'restore':
      if (current.status !== 'removed') return current
      return { ...current, status: current.joinedAt ? 'joined' : current.addedAt ? 'added' : 'new' }
  }
}

/**
 * Addresses still to be typed into Play Console (Closed testing -> Testers ->
 * email list), in the comma-separated form that box accepts. Pasting appends;
 * uploading a CSV there replaces the whole list, which is why this is a paste
 * string and not a file.
 */
export function playConsoleEmailList(rows: Array<{ email: string; status: TesterStatus }>): string {
  return rows
    .filter((r) => r.status === 'new')
    .map((r) => r.email)
    .join(', ')
}

export interface TesterProgress {
  /** Signups that have not been removed. */
  total: number
  waiting: number
  added: number
  joined: number
  removed: number
  /** How many more confirmed testers Google's minimum still needs. */
  stillNeeded: number
}

export function testerProgress(rows: Array<{ status: TesterStatus }>): TesterProgress {
  const count = (s: TesterStatus) => rows.filter((r) => r.status === s).length
  const joined = count('joined')
  const removed = count('removed')
  return {
    total: rows.length - removed,
    waiting: count('new'),
    added: count('added'),
    joined,
    removed,
    stillNeeded: Math.max(0, TESTERS_REQUIRED - joined),
  }
}
