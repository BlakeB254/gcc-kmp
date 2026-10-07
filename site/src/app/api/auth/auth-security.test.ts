/**
 * Auth hardening regression tests (sec/auth-and-payout-switch):
 *  C1 sponsor-only universal-login gets no worker Bearer token
 *  C2 2FA challenge tokens are never sessions (verify + refresh), and 2FA is
 *     enforced on universal-login / Google
 *  C3 admin role needs a VERIFIED ADMIN_EMAILS worker; Google requires
 *     email_verified and fails closed without a client id
 *  token_version revocation
 *
 * The database is a fake (src/test-support/fake-db.ts); nothing connects.
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { SignJWT, decodeJwt } from 'jose'

process.env.JWT_SECRET = 'test-secret-for-auth-security-tests-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'

import { __setDbForTests } from '@/db'
import { createFakeDb, callArg, type FakeQuery } from '@/test-support/fake-db'
import { hashPassword, createToken, verifyToken } from '@/lib/auth'
import {
  createMobileToken,
  createTwoFactorChallengeToken,
  verifyMobileToken,
  verifyTwoFactorChallengeToken,
  roleFor,
} from '@/lib/mobile-auth'
import { POST as universalLogin } from './universal-login/route'
import { POST as refresh } from './refresh/route'
import { POST as register } from './register/route'
import { POST as googleLogin } from './google/route'
import { POST as logoutAll } from './logout-all/route'

const secret = new TextEncoder().encode(process.env.JWT_SECRET)
const ADMIN = 'boss@example.org'

type Row = Record<string, unknown>

function workerRow(overrides: Row = {}): Row {
  return {
    id: 7,
    name: 'Wendy Worker',
    email: 'wendy@example.org',
    phone: '',
    hash: null,
    salt: null,
    emailVerified: true,
    verificationStatus: 'approved',
    verificationReviewNotes: null,
    verificationGovernmentIdId: null,
    verificationProofOfAddressId: null,
    twoFactorEnabled: false,
    totpSecret: null,
    twoFactorRecoveryCodes: null,
    tokenVersion: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

interface World {
  worker: Row | null
  sponsor: Row | null
}

/** Install a fake db that answers the queries these routes make. */
function install(world: World) {
  const fake = createFakeDb((q: FakeQuery) => {
    if (q.op === 'execute') {
      if (q.sqlText?.includes('rate_limit_events')) return { rows: [{ hits: 0 }] }
      if (q.sqlText?.toLowerCase().includes('from sponsors')) {
        return { rows: world.sponsor ? [world.sponsor] : [] }
      }
      return { rows: [] }
    }
    if (q.op === 'select' && q.table === 'workers') return world.worker ? [world.worker] : []
    if (q.op === 'insert' && q.table === 'workers') {
      const values = callArg(q, 'values') as Row
      world.worker = { ...workerRow(), ...values, id: 99, tokenVersion: 0 }
      return [world.worker]
    }
    if (q.op === 'update' && q.table === 'workers') {
      if (!world.worker) return []
      const set = callArg(q, 'set') as Row
      // Token-version bump arrives as an SQL expression: apply it as +1.
      const next = { ...world.worker }
      for (const [k, v] of Object.entries(set)) {
        next[k] = k === 'tokenVersion' && typeof v === 'object' ? Number(world.worker.tokenVersion) + 1 : v
      }
      world.worker = next
      return [next]
    }
    return []
  })
  __setDbForTests(fake.db)
  return fake
}

function jsonRequest(url: string, body: unknown, headers: Record<string, string> = {}) {
  return new Request(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.9', ...headers },
    body: JSON.stringify(body),
  }) as unknown as import('next/server').NextRequest
}

let savedEnv: NodeJS.ProcessEnv
let savedFetch: typeof fetch
beforeEach(() => {
  savedEnv = { ...process.env }
  savedFetch = globalThis.fetch
  process.env.ADMIN_EMAILS = ADMIN
})
afterEach(() => {
  process.env = savedEnv
  globalThis.fetch = savedFetch
  __setDbForTests(null)
})

// ── C1 ───────────────────────────────────────────────────────────────────

test('C1: sponsor-only universal-login gets no worker token and no worker cookie', async () => {
  const pw = hashPassword('sponsor-pass-1')
  install({
    worker: null,
    sponsor: { id: 7, name: 'Sam Sponsor', email: 's@example.org', type: 'business', hash: pw.hash, salt: pw.salt, email_verified: true },
  })
  const res = await universalLogin(jsonRequest('http://x/api/auth/universal-login', { email: 's@example.org', password: 'sponsor-pass-1' }))
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.success, true)
  assert.equal(body.accountType, 'sponsor')
  assert.equal(body.token, null, 'a sponsor must never receive a worker-scoped Bearer token')
  assert.deepEqual(body.roles, ['sponsor'])
  assert.equal(res.cookies.get('worker-token'), undefined)
  assert.ok(res.cookies.get('sponsor-token'))
})

test('C1: sponsor password matching while a worker row exists still mints no worker token', async () => {
  const sp = hashPassword('sponsor-pass-1')
  const wk = hashPassword('different-worker-pass')
  install({
    worker: workerRow({ id: 3, email: 'dual@example.org', hash: wk.hash, salt: wk.salt }),
    sponsor: { id: 3, name: 'Dual', email: 'dual@example.org', type: 'individual', hash: sp.hash, salt: sp.salt, email_verified: true },
  })
  const res = await universalLogin(jsonRequest('http://x/api/auth/universal-login', { email: 'dual@example.org', password: 'sponsor-pass-1' }))
  const body = await res.json()
  assert.equal(body.success, true)
  assert.equal(body.token, null)
  // The sponsor password must not open the worker account's web session either.
  assert.equal(res.cookies.get('worker-token'), undefined, 'no worker cookie from the sponsor password')
  assert.deepEqual(body.roles, ['sponsor'])
  assert.ok(res.cookies.get('sponsor-token'))
})

test('C1: worker password matching while a sponsor row exists issues no sponsor cookie', async () => {
  const sp = hashPassword('different-sponsor-pass')
  const wk = hashPassword('worker-pass-1')
  install({
    worker: workerRow({ id: 3, email: 'dual@example.org', hash: wk.hash, salt: wk.salt }),
    sponsor: { id: 3, name: 'Dual', email: 'dual@example.org', type: 'individual', hash: sp.hash, salt: sp.salt, email_verified: true },
  })
  const res = await universalLogin(jsonRequest('http://x/api/auth/universal-login', { email: 'dual@example.org', password: 'worker-pass-1' }))
  const body = await res.json()
  assert.equal(body.success, true)
  assert.ok(res.cookies.get('worker-token'))
  assert.equal(res.cookies.get('sponsor-token'), undefined, 'no sponsor cookie from the worker password')
})

test('C1: worker password login gets a worker token for that worker id', async () => {
  const wk = hashPassword('worker-pass-1')
  install({ worker: workerRow({ id: 12, hash: wk.hash, salt: wk.salt }), sponsor: null })
  const res = await universalLogin(jsonRequest('http://x/api/auth/universal-login', { email: 'wendy@example.org', password: 'worker-pass-1' }))
  const body = await res.json()
  assert.equal(body.success, true)
  assert.equal(decodeJwt(body.token).worker_id, 12)
  assert.ok(res.cookies.get('worker-token'))
})

test('C2: universal-login demands the second factor for a 2FA worker and issues nothing without it', async () => {
  const wk = hashPassword('worker-pass-1')
  install({
    worker: workerRow({ hash: wk.hash, salt: wk.salt, twoFactorEnabled: true, totpSecret: 'JBSWY3DPEHPK3PXP' }),
    sponsor: null,
  })
  const noCode = await universalLogin(jsonRequest('http://x/api/auth/universal-login', { email: 'wendy@example.org', password: 'worker-pass-1' }))
  assert.equal(noCode.status, 401)
  assert.equal((await noCode.json()).code, 'TWO_FACTOR_REQUIRED')
  assert.equal(noCode.cookies.get('worker-token'), undefined)

  const badCode = await universalLogin(
    jsonRequest('http://x/api/auth/universal-login', { email: 'wendy@example.org', password: 'worker-pass-1', twoFactorCode: '000000' }),
  )
  assert.equal(badCode.status, 401)
  const body = await badCode.json()
  assert.equal(body.code, 'TWO_FACTOR_INVALID')
  assert.equal(body.token, undefined)
})

// ── C2 ───────────────────────────────────────────────────────────────────

test('C2: a 2FA challenge token is rejected by verifyMobileToken', async () => {
  install({ worker: workerRow(), sponsor: null })
  const challenge = await createTwoFactorChallengeToken(7, 'wendy@example.org')
  assert.equal(await verifyMobileToken(challenge), null)
  // …but still works for its own purpose.
  assert.deepEqual(await verifyTwoFactorChallengeToken(challenge), { workerId: 7, email: 'wendy@example.org' })
})

test('C2: a 2FA challenge token cannot be exchanged at /api/auth/refresh', async () => {
  install({ worker: workerRow(), sponsor: null })
  const challenge = await createTwoFactorChallengeToken(7, 'wendy@example.org')
  const res = await refresh(jsonRequest('http://x/api/auth/refresh', {}, { authorization: `Bearer ${challenge}` }))
  assert.equal(res.status, 401)
})

test('C2: any token with a purpose claim is rejected even on the session audience', async () => {
  install({ worker: workerRow(), sponsor: null })
  const forged = await new SignJWT({ worker_id: 7, role: 'admin', purpose: '2fa-challenge' })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer('gcc-api')
    .setAudience('gcc-app')
    .setExpirationTime('5m')
    .sign(secret)
  assert.equal(await verifyMobileToken(forged), null)
})

test('C2: a token without the session claims is rejected', async () => {
  install({ worker: workerRow(), sponsor: null })
  const bare = await new SignJWT({ email: 'wendy@example.org' })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer('gcc-api')
    .setAudience('gcc-app')
    .setExpirationTime('5m')
    .sign(secret)
  assert.equal(await verifyMobileToken(bare), null)
})

test('C2: a session token is NOT accepted as a 2FA challenge', async () => {
  const { token } = await createMobileToken({ id: 7, email: 'wendy@example.org', emailVerified: true, tokenVersion: 0 })
  assert.equal(await verifyTwoFactorChallengeToken(token), null)
})

test('C2: refresh still works for a real session token', async () => {
  install({ worker: workerRow(), sponsor: null })
  const { token } = await createMobileToken({ id: 7, email: 'wendy@example.org', emailVerified: true, tokenVersion: 0 })
  const res = await refresh(jsonRequest('http://x/api/auth/refresh', {}, { authorization: `Bearer ${token}` }))
  assert.equal(res.status, 200)
  assert.ok((await res.json()).data.token)
})

// ── C3 ───────────────────────────────────────────────────────────────────

test('C3: admin role requires a verified ADMIN_EMAILS worker', () => {
  assert.equal(roleFor({ email: ADMIN, emailVerified: true }), 'admin')
  assert.equal(roleFor({ email: ADMIN.toUpperCase(), emailVerified: true }), 'admin')
  assert.equal(roleFor({ email: ADMIN, emailVerified: false }), 'worker')
  assert.equal(roleFor({ email: ADMIN, emailVerified: null }), 'worker')
  assert.equal(roleFor({ email: 'someone@example.org', emailVerified: true }), 'worker')
})

test('C3: register response carries no admin role even for an ADMIN_EMAILS address', async () => {
  install({ worker: null, sponsor: null })
  const res = await register(jsonRequest('http://x/api/auth/register', { name: 'Mallory', email: ADMIN, password: 'pw-12345678' }))
  assert.equal(res.status, 201)
  const body = await res.json()
  assert.equal(decodeJwt(body.data.token).role, 'worker')
  // and the token keeps verifying as worker while the email is unverified
  const verified = await verifyMobileToken(body.data.token)
  assert.equal(verified?.role, 'worker')
})

test('C3: verifyMobileToken recomputes the role from the DB, ignoring a stale admin claim', async () => {
  install({ worker: workerRow({ email: ADMIN, emailVerified: false }), sponsor: null })
  const forgedAdmin = await new SignJWT({ worker_id: 7, role: 'admin', tv: 0 })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer('gcc-api')
    .setAudience('gcc-app')
    .setExpirationTime('5m')
    .sign(secret)
  assert.equal((await verifyMobileToken(forgedAdmin))?.role, 'worker')
})

function mockGoogle(tokenInfo: Record<string, unknown>) {
  const calls: string[] = []
  globalThis.fetch = (async (url: string | URL) => {
    calls.push(String(url))
    return new Response(JSON.stringify(tokenInfo), { status: 200, headers: { 'content-type': 'application/json' } })
  }) as typeof fetch
  return calls
}

const GOOD_AUD = 'ios-client.apps.googleusercontent.com'

test('C3: Google sign-in fails closed (503) when no client id is configured', async () => {
  delete process.env.GOOGLE_IOS_CLIENT_ID
  delete process.env.GOOGLE_WEB_CLIENT_ID
  install({ worker: workerRow(), sponsor: null })
  const calls = mockGoogle({ sub: '1', email: 'wendy@example.org', email_verified: 'true', aud: 'anything' })
  const res = await googleLogin(jsonRequest('http://x/api/auth/google', { idToken: 'tok' }))
  assert.equal(res.status, 503)
  assert.match((await res.json()).message, /not configured/)
  assert.equal(calls.length, 0, 'must refuse before trusting any token')
})

test('C3: Google sign-in rejects an unverified email', async () => {
  process.env.GOOGLE_IOS_CLIENT_ID = GOOD_AUD
  const fake = install({ worker: workerRow(), sponsor: null })
  mockGoogle({ sub: '1', email: 'wendy@example.org', email_verified: 'false', aud: GOOD_AUD })
  const res = await googleLogin(jsonRequest('http://x/api/auth/google', { idToken: 'tok' }))
  assert.equal(res.status, 401)
  assert.equal(fake.log.filter((q) => q.table === 'workers').length, 0, 'no worker lookup/link for an unverified email')
})

test('C3: Google sign-in rejects a token for another OAuth client', async () => {
  process.env.GOOGLE_IOS_CLIENT_ID = GOOD_AUD
  install({ worker: workerRow(), sponsor: null })
  mockGoogle({ sub: '1', email: 'wendy@example.org', email_verified: 'true', aud: 'evil.apps.googleusercontent.com' })
  const res = await googleLogin(jsonRequest('http://x/api/auth/google', { idToken: 'tok' }))
  assert.equal(res.status, 401)
})

test('C3: Google sign-in with a verified email and right aud succeeds', async () => {
  process.env.GOOGLE_IOS_CLIENT_ID = GOOD_AUD
  install({ worker: workerRow(), sponsor: null })
  mockGoogle({ sub: '1', email: 'wendy@example.org', email_verified: 'true', aud: GOOD_AUD })
  const res = await googleLogin(jsonRequest('http://x/api/auth/google', { idToken: 'tok' }))
  assert.equal(res.status, 200)
  assert.equal(decodeJwt((await res.json()).data.token).worker_id, 7)
})

test('C2: Google sign-in for a 2FA worker returns a challenge, not a session', async () => {
  process.env.GOOGLE_IOS_CLIENT_ID = GOOD_AUD
  install({ worker: workerRow({ twoFactorEnabled: true, totpSecret: 'JBSWY3DPEHPK3PXP' }), sponsor: null })
  mockGoogle({ sub: '1', email: 'wendy@example.org', email_verified: 'true', aud: GOOD_AUD })
  const res = await googleLogin(jsonRequest('http://x/api/auth/google', { idToken: 'tok' }))
  const body = await res.json()
  assert.equal(body.data.requiresTwoFactor, true)
  assert.equal(body.data.token, null)
  assert.equal(await verifyMobileToken(body.data.twoFactorToken), null)
})

// ── token_version ────────────────────────────────────────────────────────

test('token_version: a token minted at an old version is rejected', async () => {
  install({ worker: workerRow({ tokenVersion: 1 }), sponsor: null })
  const { token: stale } = await createMobileToken({ id: 7, email: 'wendy@example.org', emailVerified: true, tokenVersion: 0 })
  const { token: current } = await createMobileToken({ id: 7, email: 'wendy@example.org', emailVerified: true, tokenVersion: 1 })
  assert.equal(await verifyMobileToken(stale), null)
  assert.deepEqual(await verifyMobileToken(current), { workerId: 7, role: 'worker' })
})

test('token_version: legacy tokens (no tv claim) count as version 0', async () => {
  const legacy = await new SignJWT({ worker_id: 7, role: 'worker' })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer('gcc-api')
    .setAudience('gcc-app')
    .setExpirationTime('5m')
    .sign(secret)
  install({ worker: workerRow({ tokenVersion: 0 }), sponsor: null })
  assert.equal((await verifyMobileToken(legacy))?.workerId, 7)
  install({ worker: workerRow({ tokenVersion: 1 }), sponsor: null })
  assert.equal(await verifyMobileToken(legacy), null)
})

test('token_version: a deleted worker’s token is rejected', async () => {
  install({ worker: null, sponsor: null })
  const { token } = await createMobileToken({ id: 7, email: 'wendy@example.org', emailVerified: true, tokenVersion: 0 })
  assert.equal(await verifyMobileToken(token), null)
})

test('token_version: logout-all revokes the calling session', async () => {
  const world: World = { worker: workerRow({ tokenVersion: 0 }), sponsor: null }
  install(world)
  const { token } = await createMobileToken({ id: 7, email: 'wendy@example.org', emailVerified: true, tokenVersion: 0 })
  const res = await logoutAll(jsonRequest('http://x/api/auth/logout-all', {}, { authorization: `Bearer ${token}` }))
  assert.equal(res.status, 200)
  assert.equal(world.worker?.tokenVersion, 1)
  assert.equal(await verifyMobileToken(token), null)
})

test('token_version: a worker web cookie carries tv and dies when the version is bumped', async () => {
  const world: World = { worker: workerRow({ tokenVersion: 2 }), sponsor: null }
  install(world)
  const cookie = await createToken({ id: 7, email: 'wendy@example.org', type: 'worker' })
  assert.equal(decodeJwt(cookie).tv, 2)
  assert.equal((await verifyToken(cookie))?.id, 7)
  world.worker = workerRow({ tokenVersion: 3 })
  install(world)
  assert.equal(await verifyToken(cookie), null, 'logout-all / password change must end web sessions too')
})

test('token_version: a legacy worker web cookie (no tv) counts as version 0', async () => {
  const legacy = await new SignJWT({ id: 7, email: 'wendy@example.org', type: 'worker' })
    .setProtectedHeader({ alg: 'HS256' })
    .setExpirationTime('5m')
    .sign(secret)
  install({ worker: workerRow({ tokenVersion: 0 }), sponsor: null })
  assert.equal((await verifyToken(legacy))?.id, 7)
  install({ worker: workerRow({ tokenVersion: 1 }), sponsor: null })
  assert.equal(await verifyToken(legacy), null)
  install({ worker: null, sponsor: null })
  assert.equal(await verifyToken(legacy), null, 'deleted worker')
})

test('token_version: sponsor web cookies are unaffected by worker versions', async () => {
  install({ worker: null, sponsor: null })
  const cookie = await createToken({ id: 9, email: 's@example.org', type: 'sponsor' })
  assert.equal((await verifyToken(cookie))?.type, 'sponsor')
})
