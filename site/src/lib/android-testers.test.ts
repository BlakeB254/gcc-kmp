import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ANDROID_TESTING_OPT_IN_URL,
  TESTERS_REQUIRED,
  nextTesterState,
  normalizeTesterEmail,
  normalizeTesterName,
  playConsoleEmailList,
  testerGroupUrl,
  testerProgress,
} from './android-testers'

// Pure rules behind the Android closed-test signup flow (/android-beta, the
// public POST /api/android-testers and /admin/android-testers). Run with:
//   npx tsx --test src/lib/android-testers.test.ts
// No DATABASE_URL needed — android-testers.ts has no runtime `@/db` import.

// ── normalizeTesterEmail ──────────────────────────────────────────────────

test('normalizeTesterEmail: trims and lowercases, so one Google account is one row', () => {
  assert.equal(normalizeTesterEmail('  Keith.Lewis@Gmail.com '), 'keith.lewis@gmail.com')
})

test('normalizeTesterEmail: rejects anything Play Console would refuse', () => {
  for (const bad of ['', 'not-an-email', 'a@b', 'two words@gmail.com', 'a@b.com,c@d.com', 42, null]) {
    assert.equal(normalizeTesterEmail(bad), null, `expected ${JSON.stringify(bad)} to be rejected`)
  }
})

test('normalizeTesterEmail: control characters are rejected, not passed to the database', () => {
  assert.equal(normalizeTesterEmail('a\u0000b@gmail.com'), null)
  assert.equal(normalizeTesterEmail('ab@gmail\u0007.com'), null)
  assert.equal(normalizeTesterEmail('ab\u007f@gmail.com'), null)
})

test('testerGroupUrl: reads the server-side setting when no value is passed', () => {
  const before = process.env.ANDROID_TESTER_GROUP_URL
  process.env.ANDROID_TESTER_GROUP_URL = 'https://groups.google.com/g/gcc-android-testers'
  try {
    assert.equal(testerGroupUrl(), 'https://groups.google.com/g/gcc-android-testers')
  } finally {
    if (before === undefined) delete process.env.ANDROID_TESTER_GROUP_URL
    else process.env.ANDROID_TESTER_GROUP_URL = before
  }
})

test('normalizeTesterEmail: accepts non-Gmail Google accounts', () => {
  assert.equal(normalizeTesterEmail('someone@uic.edu'), 'someone@uic.edu')
})

// ── normalizeTesterName ───────────────────────────────────────────────────

test('normalizeTesterName: keeps real names, including accents and apostrophes', () => {
  assert.equal(normalizeTesterName('  Keith  '), 'Keith')
  assert.equal(normalizeTesterName("D'Andre"), "D'Andre")
  assert.equal(normalizeTesterName('José María'), 'José María')
  assert.equal(normalizeTesterName('Mary-Jane'), 'Mary-Jane')
})

test('normalizeTesterName: a name with a full stop is not stored, because a domain looks the same', () => {
  assert.equal(normalizeTesterName('J.R.'), null)
  assert.equal(normalizeTesterName('evil.example'), null)
})

test('normalizeTesterName: drops anything that could carry a link, markup or a message', () => {
  for (const bad of ['', '   ', 'http://evil.example', 'visit evil.example now', '<b>hi</b>', 'a@b.com', 'x'.repeat(61), 7, null]) {
    assert.equal(normalizeTesterName(bad), null, `expected ${JSON.stringify(bad)} to be dropped`)
  }
})

// ── testerGroupUrl ────────────────────────────────────────────────────────

test('testerGroupUrl: unset means there is no self-serve group', () => {
  assert.equal(testerGroupUrl(undefined), null)
  assert.equal(testerGroupUrl('  '), null)
})

test('testerGroupUrl: only a groups.google.com https link is ever shown to visitors', () => {
  assert.equal(
    testerGroupUrl('https://groups.google.com/g/gcc-android-testers'),
    'https://groups.google.com/g/gcc-android-testers',
  )
  assert.equal(testerGroupUrl('http://groups.google.com/g/gcc-android-testers'), null)
  assert.equal(testerGroupUrl('https://groups.google.com.evil.example/g/x'), null)
  assert.equal(testerGroupUrl('gcc-android-testers@googlegroups.com'), null)
})

// ── nextTesterState ───────────────────────────────────────────────────────

const NOW = '2026-10-05T12:00:00.000Z'

test('nextTesterState: invite moves a new signup onto the list and stamps addedAt once', () => {
  assert.deepEqual(nextTesterState({ status: 'new', addedAt: null, joinedAt: null }, 'invite', NOW), {
    status: 'added',
    addedAt: NOW,
    joinedAt: null,
  })
})

test('nextTesterState: re-inviting never demotes a tester who already joined', () => {
  const joined = { status: 'joined' as const, addedAt: '2026-10-01T00:00:00.000Z', joinedAt: '2026-10-02T00:00:00.000Z' }
  assert.deepEqual(nextTesterState(joined, 'invite', NOW), joined)
})

test('nextTesterState: joined stamps joinedAt and backfills addedAt', () => {
  assert.deepEqual(nextTesterState({ status: 'new', addedAt: null, joinedAt: null }, 'joined', NOW), {
    status: 'joined',
    addedAt: NOW,
    joinedAt: NOW,
  })
})

test('nextTesterState: remove keeps the timestamps, restore returns to where they were', () => {
  const added = { status: 'added' as const, addedAt: '2026-10-01T00:00:00.000Z', joinedAt: null }
  const removed = nextTesterState(added, 'remove', NOW)
  assert.equal(removed.status, 'removed')
  assert.equal(removed.addedAt, added.addedAt)
  assert.equal(nextTesterState(removed, 'restore', NOW).status, 'added')
  assert.equal(
    nextTesterState({ status: 'removed', addedAt: null, joinedAt: null }, 'restore', NOW).status,
    'new',
  )
})

// ── playConsoleEmailList ──────────────────────────────────────────────────

test('playConsoleEmailList: only signups not yet on the Play list, comma-separated for pasting', () => {
  assert.equal(
    playConsoleEmailList([
      { email: 'a@gmail.com', status: 'new' },
      { email: 'b@gmail.com', status: 'added' },
      { email: 'c@gmail.com', status: 'new' },
      { email: 'd@gmail.com', status: 'removed' },
      { email: 'e@gmail.com', status: 'joined' },
    ]),
    'a@gmail.com, c@gmail.com',
  )
})

// ── testerProgress ────────────────────────────────────────────────────────

test('testerProgress: counts by status and how many more must join for Google', () => {
  const rows = [
    { status: 'new' as const },
    { status: 'added' as const },
    { status: 'added' as const },
    { status: 'joined' as const },
    { status: 'removed' as const },
  ]
  assert.deepEqual(testerProgress(rows), {
    total: 4,
    waiting: 1,
    added: 2,
    joined: 1,
    removed: 1,
    stillNeeded: TESTERS_REQUIRED - 1,
  })
})

test('testerProgress: stillNeeded never goes negative', () => {
  const rows = Array.from({ length: TESTERS_REQUIRED + 3 }, () => ({ status: 'joined' as const }))
  assert.equal(testerProgress(rows).stillNeeded, 0)
})

test('the opt-in link is the closed-test join page for the published package', () => {
  assert.equal(
    ANDROID_TESTING_OPT_IN_URL,
    'https://play.google.com/apps/testing/com.globalcommunitycleaners.worker',
  )
})
