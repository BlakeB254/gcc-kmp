import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, existsSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Every API route is either guarded or deliberately public — and says which.
 *
 * GET /api/jobs (#289) and GET /api/jobs/[id] returned every job's workerId,
 * routeId, timing and payout to anyone. Nothing had ever said "this route must
 * authenticate"; it was a habit, and habits drift. This test makes it a gate:
 * a route.ts that calls no known auth helper must appear in PUBLIC_ROUTES
 * with a reason, or the suite fails naming it.
 *
 * The allowlist is kept honest in the other direction too: an entry whose
 * route no longer exists, or which now calls a guard, fails as stale.
 *
 * Adding a route: either call one of the helpers in GUARD, or add it below.
 * Adding a helper: extend GUARD.
 */

const API_ROOT = dirname(fileURLToPath(import.meta.url))

/** Every way a handler in this codebase authenticates its caller. */
const GUARD =
  /\b(requireAdmin|requireMobileAuth|requireSponsor|verifyMobileToken|verifyWorkerToken|verifyToken\(|verifyAdmin|closeRouteHandler|CRON_SECRET|constructEvent|verifyUnsubscribeToken|verifyMagicLink|verifyPhoneRegistrationToken|verifyTwoFactorChallengeToken|verifyAppleIdToken|verifyGoogleIdToken|verifyCode\(|verifyPassword|SERVICE_TOKEN|x-service-token)\b/

/**
 * Routes that are public on purpose. Path is relative to app/api, without
 * the trailing /route.ts. Keep the reason honest — it is the review record.
 */
const PUBLIC_ROUTES: Record<string, string> = {
  // ── Public reads: website map, stats, feeds, listings ─────────────────
  'affected-areas': 'public map layer (block polygons + funding intensity)',
  'analytics/cleanliness': 'public cleanliness score',
  'analytics/cleanliness/[area]': 'public cleanliness score per area',
  'articles': 'public blog listing',
  'articles/[slug]': 'public blog article',
  'blocks': 'public block listing for the website map',
  'bounties/[id]': 'public bounty detail (bounties page is public)',
  'civic/stats': 'public civic stats',
  'community-reports/map': 'public spot markers',
  'feed': 'public activity feed',
  'health': 'health check',
  'leaderboard': 'public leaderboard',
  'map/buildings': 'public map layer',
  'map/coverage': 'public map layer',
  'map/observations': 'public map layer; media gated by servableMediaUrl()',
  'movement/stats': 'public movement stats',
  'public/data-export': 'public data export',
  'public/data-export/stats': 'public data export stats',
  'public/impact-stats': 'public impact stats',
  'public/sponsor/[id]': 'public sponsor profile',
  'public/sponsor-wall': 'public sponsor wall',
  'public/stats': 'public stats',
  'public/support-wall': 'public support wall',
  'routes': 'public published-route listing for the website map',
  'scan-config': 'public scan configuration for the app (no secrets)',
  'sponsor-packages': 'public package listing',
  'sponsor-packages/walkthrough': 'public package walkthrough',
  'workers/me/service-area':
    'public aggregate block counts for a hardcoded service area; carries no per-worker data despite the me/ path (rename candidate)',

  // ── Signup, checkout, password and verification flows (pre-auth) ──────
  'android-testers': 'Android closed-test signup form; rate-limited, stores an address only',
  'auth/apple': 'sign-in entry point',
  'auth/google': 'sign-in entry point',
  'auth/logout': 'clears the session cookie',
  'auth/magic-link': 'requests a magic link',
  'auth/register': 'account creation',
  'auth/web/start': 'OAuth start',
  'contribute/checkout': 'donation checkout (Stripe)',
  'material-donations': 'in-kind donation intake form',
  'sponsor-packages/checkout': 'sponsor checkout (Stripe)',
  'sponsors/check-duplicate': 'signup email check',
  'sponsors/checkout': 'sponsor signup + checkout',
  'sponsors/forgot-password': 'password reset request',
  'sponsors/logout': 'clears the sponsor cookie',
  'sponsors/register': 'account creation',
  'sponsors/reset-password': 'password reset with emailed token',
  'sponsors/verify-email': 'email verification with emailed token',
  'waitlist/join': 'waitlist signup',
  'waitlist/profile': 'waitlist profile by emailed token',
  'waitlist/update': 'waitlist update by emailed token',
  'workers/apply': 'worker application',
  'workers/check-duplicate': 'signup email check',
  'workers/forgot-password': 'password reset request',
  'workers/request-code': 'phone code request',
  'workers/resend-verification': 'resend verification email',
  'workers/reset-password': 'password reset with emailed token',
  'workers/set-password': 'set password with emailed token',
  'workers/verify-email': 'email verification with emailed token',

  // ── Deliberately unauthenticated ingest ───────────────────────────────
  'crashes': 'crash telemetry; documented in the handler, deviceId-throttled',
  'crashes/diagnostics': 'crash diagnostics; documented in the handler, deviceId-throttled',

  // ── Stubs: fixed payloads or 501, read nothing per-user ───────────────
  'admin/newsletter/preview': 'renders the newsletter template with no data; documented in the handler',
  'bounties/[id]/accept': '501 stub',
  'bounties/[id]/claim': '501 stub',
  'gamification/me/badges': 'stub returning an empty list',
  'spots/[id]/volunteer/[cleanupId]/complete': '501 stub',
  'wallet': 'stub returning a zeroed wallet',
  'wallet/allocate': 'stub',
  'wallet/deposit': 'stub',
  'wallet/transactions': 'stub',
}

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else if (entry.name === 'route.ts') out.push(full)
  }
  return out
}

const REEXPORT = /^\s*export\s*\{[^}]*\}\s*from\s*'([^']+)'\s*;?\s*$/m

/** A file that only re-exports another route inherits that route's guard. */
function isGuarded(file: string, seen = new Set<string>()): boolean {
  if (seen.has(file)) return false
  seen.add(file)
  const src = readFileSync(file, 'utf8')
  if (GUARD.test(src)) return true
  const m = src.match(REEXPORT)
  if (m) {
    const target = resolve(dirname(file), m[1].endsWith('.ts') ? m[1] : `${m[1]}.ts`)
    if (existsSync(target)) return isGuarded(target, seen)
  }
  return false
}

const routeKey = (file: string) => relative(API_ROOT, dirname(file)).split('\\').join('/')

test('every API route is guarded or listed as public with a reason', () => {
  const offenders = walk(API_ROOT)
    .filter((f) => !isGuarded(f) && !(routeKey(f) in PUBLIC_ROUTES))
    .map(routeKey)
  assert.deepEqual(
    offenders,
    [],
    `Unguarded routes with no PUBLIC_ROUTES entry:\n  ${offenders.join('\n  ')}\n` +
      'Call one of the helpers in GUARD, or add the route to PUBLIC_ROUTES with a reason.'
  )
})

test('every PUBLIC_ROUTES entry exists and is still unguarded', () => {
  const stale: string[] = []
  for (const key of Object.keys(PUBLIC_ROUTES)) {
    const file = join(API_ROOT, key, 'route.ts')
    if (!existsSync(file)) stale.push(`${key} (route no longer exists)`)
    else if (isGuarded(file)) stale.push(`${key} (now guarded — remove it from PUBLIC_ROUTES)`)
  }
  assert.deepEqual(stale, [], `Stale PUBLIC_ROUTES entries:\n  ${stale.join('\n  ')}`)
})

test('every public route has a non-empty reason', () => {
  const missing = Object.entries(PUBLIC_ROUTES)
    .filter(([, reason]) => !reason.trim())
    .map(([k]) => k)
  assert.deepEqual(missing, [])
})

test('the job endpoints are guarded (the leak that motivated this gate)', () => {
  assert.equal(isGuarded(join(API_ROOT, 'jobs', 'route.ts')), true)
  assert.equal(isGuarded(join(API_ROOT, 'jobs', '[id]', 'route.ts')), true)
})
