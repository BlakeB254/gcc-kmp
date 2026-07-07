# Codebase audit — 2026-07-07

Full-repo evaluation with `site/` (Next.js) and the iOS app (`composeApp/` + `iosApp/` +
`shared/`) treated as the primary platforms. Four passes: site dead code, site
quality/patterns, mobile/shared dead code + quality, and a feature-by-feature audit of the
iOS app's backend connectivity. Findings are ranked; file:line references verified at audit
time.

---

## 0. The one architectural finding to internalize first

**The mobile app does not talk to the Ktor server.** Both mobile platforms hardcode
`https://api.globalcommunitycleaners.org` (`composeApp/.../iosMain/.../PlatformConfig.kt:8`,
`androidMain/.../PlatformConfig.kt:7`), and `site/src/middleware.ts:19-27` shows the `api.`
subdomain is served by **Next.js** (passes `/api/*` through, 301s everything else). Every one
of the ~120 client calls in `GccApiClient.kt` has a matching Next.js route; the Ktor
`server/` implements only a subset, diverges on several paths (`/api/auth/refresh`,
`/api/wallet`, `/api/gamification/me/badges`, all of `/api/scans/coverage*`, most admin
routes are missing), and its login flow force-2FAs every non-demo account without ever
delivering the code (`AuthService.kt:99-139`) — real login against Ktor is impossible.

Consequence: the Ktor server is effectively dead code as a mobile backend, CLAUDE.md's
framing of it as "the backend" is misleading, and the app is one `getApiBaseUrl()` edit away
from a broken server. Decide: retire Ktor as the mobile backend (document Next.js as
canonical) or bring it to parity. Either way, document reality.

---

## 1. Critical fixes (do these first)

1. **Government IDs uploaded to a public bucket (privacy/compliance).**
   `DocumentUploadViewModel.kt:66` sends ID + proof-of-address photos through
   `uploadSpotPhotos` → `POST /api/spots/upload`, which writes to Vercel Blob with
   `access: 'public'` and returns publicly fetchable URLs
   (`site/src/app/api/spots/upload/route.ts:38-45`). Route verification docs through a
   private, auth-gated endpoint — the app already has a private presigned-upload path
   (`GccApiClient.kt:1173`) that can be reused.

2. **Stripe webhook is not idempotent for the money path.** No processed-events guard and no
   unique index on `transactions.stripeCheckoutSessionId` (`db/schema.ts:740`). A Stripe
   retry of `checkout.session.completed` re-inserts transactions, re-marks blocks funded,
   and double-increments sponsor stats. Add a `stripe_events(event_id pk)` insert-guard at
   the top of the handler + a unique index on the session id.
   (`site/src/app/api/stripe/webhooks/route.ts:86`)

3. **Race-prone stat counters.** The webhook does read-modify-write on totals
   (`stripe/webhooks/route.ts:174-182, 244-253`); concurrent events corrupt them. The
   correct atomic-increment pattern already exists at
   `admin/workers/[id]/points/route.ts:177` — copy it.

4. **No transactions anywhere.** `db/index.ts` uses the `neon-http` driver, which cannot do
   transactions, so checkout/webhook/points flows (3–5 sequential writes) can partially
   fail. Move those paths to `drizzle-orm/neon-serverless` (WebSocket Pool).

5. **iOS push is dead in production.** `iosApp/iosApp/iosApp.entitlements` sets
   `aps-environment = development`; TestFlight/App Store builds register APNs tokens the
   production push service can't deliver to. Switch to `production` for release builds.

6. **iOS deep-link auth callback can never open the app.** `DeepLinkParser.kt` handles
   `gcc://auth/callback?token=…`, but `iosApp/Info.plist:27-33` registers only the Google
   reversed-client-id scheme — no `gcc://`. Universal links cover only the apex domain
   (`applinks:globalcommunitycleaners.org`) while OAuth/site links use `www.`. Add the `gcc`
   scheme and `applinks:www.globalcommunitycleaners.org`.

---

## 2. iOS app ↔ backend, feature by feature

| Feature | Status | Headline |
|---|---|---|
| Email/password login + 2FA | SOLID | `authData.token!!` force-unwraps (`LoginViewModel.kt:63,93,139,198`, `BiometricAuthViewModel.kt:68`) crash on a malformed 2xx |
| Apple / Google social auth | SOLID | Hardcoded `www.` host bypasses `baseUrl`; skips `checkUnauthorized` |
| Biometric login + token storage | GAPS | Keychain-backed (good) but stores the **raw password** for re-auth (`TokenManager.kt:96-99`); stale KDoc claims NSUserDefaults |
| Token refresh / 401 handling | SOLID | Proactive refresh with mutex + double-check (`AuthTokenProvider.kt:26-55`) — strongest subsystem in the codebase |
| Jobs (claim/start/complete/evidence) | SOLID | No retry; assumes connectivity |
| Route scan (scout/clean/coverage/AR) | GAPS | Coroutine/GPS leak (below); coverage queue has proper retry |
| Verification document upload | **BROKEN** | Public-bucket PII issue (§1.1) |
| Env-scan captures (R2 presigned) | SOLID | Careful raw-URL encoding, 501 handled |
| Wallet / payouts / Stripe Connect | SOLID (live) | Ktor path mismatches are latent traps |
| Push notifications | GAPS | `aps-environment=development` (§1.5) |
| Leaderboard / feed / impact / articles | SOLID | Sponsor wall parses loosely, degrades to empty list |

Cross-cutting:

- **No HTTP timeouts, no retry/backoff** on either Ktor client (`GccApiClient.kt:28-47`).
  Install `HttpTimeout`; add bounded retry for idempotent GETs.
- **`RouteScanViewModel` leaks per scan session.** `onCleared()` (`:1326-1336`) cancels the
  tracked jobs but not `accuracyJob` (`:245`) nor the collectors at `:544` and `:1056`, and
  never calls `scope.cancel()` — location observation survives screen exit (battery/GPS
  drain). Registered as Koin `factory` (`AppModule.kt:111`) so it compounds per visit.
- **Base URL is hardcoded prod on all platforms**; desktop points at a dead
  `localhost:3000`; the commonMain KDoc claiming "simulator: localhost" is false. Six extra
  hardcoded hosts inside `GccApiClient` bypass `baseUrl` entirely (`:218, :373, :421, :434,
  :484, :751`). Introduce per-environment config.
- **Error-handling duplication:** ~20 methods hand-roll the raw
  `httpClient.get/post` + status-check block instead of the shared envelope helpers
  (`:85-210`); the site-host calls skip `checkUnauthorized`, so a 401 there won't force
  logout. Add non-envelope helper variants and route everything through them.
- Serialization is forward-compatible (`GccJson`: `ignoreUnknownKeys`, lenient, coerce) —
  new server fields won't crash old builds. Keep it that way.
- Info.plist permissions all present and justified; ATS is clean (HTTPS-only + local
  networking); no token logging anywhere. Good.

Positives to preserve: proactive token refresh, Keychain storage, the coverage-event retry
queue, image compression before upload (1920px / JPEG 0.8), the presigned-R2 upload path.

---

## 3. Dead code

### site/ (≈7,200 LOC total; ~4,100 CERTAIN)

- **26 never-imported components (~3,950 LOC), CERTAIN** — abandoned landing/CTA variants
  and unused primitives: `MobileAppCTASection`, `SponsorCTASection`,
  `HowItWorksSponsorsSection`, `CorporatePartnerSection`, `HowItWorksSection`,
  `WorkerCTASection`, `WaitlistExpansionSection`, `TestimonialsSection`,
  `WaitlistCTASection`, `waitlist/ProfileCard`, `forms/FileUpload`, `forms/StepIndicator`,
  all of `flow/clean/*` (3), `flow/fund/BlockSelector`, `flow/display/ImpactMeter`,
  `share/*` (2), `mobile/*` (4), `shared/SectionWrapper`, `shared/CTABanner`,
  `shared/PageHero`. The 8 barrel `index.ts` files are also unreferenced.
- **Orphaned API routes (~1,700 LOC), CERTAIN/LIKELY** — no web or mobile caller:
  the whole `community-reports` feature (5 routes, 492 LOC — verify not staged for mobile),
  the phone-verification trio (`register-phone`, `verify-phone`, `request-code`),
  `sponsors/checkout` (superseded by `sponsor-packages/checkout`), `public/data-export`,
  `public/contributions`, `waitlist/check-area`, `auth/magic-link`, the exclusion-zone
  `[zoneId]` DELETE, `admin/workers/[id]/connect/onboard`.
- **6 unused npm dependencies, CERTAIN**: `react-leaflet`, `@stripe/stripe-js`, `docx`,
  `embla-carousel-react`, `@turf/boolean-overlap`, `@types/mapbox-gl`.
- **34 dead CSS classes + 10 dead keyframes (~180 LOC)** in `globals.css` (holographic-*,
  money-rain, card-flip, etc. are used by flow/share components that are themselves dead).
- **Dead lib exports**: `workerSignupEmail` (122 LOC, CERTAIN); most of
  `lib/stripe/connect.ts` and `lib/totp.ts` exports; ~20 more scattered symbols (LIKELY).
- **Orphaned seed scripts** (`seed-spots.ts` + its two data files, ~574 LOC) — not reachable
  from `npm run seed`; confirm they're not run manually before deleting.
- Orphaned pages (no inbound link; may be email-linked): `/business`, `/business/dashboard`,
  `/donate/wall`, `/sponsor/receipts/tx/[txId]`.

### shared/ + composeApp/ (~150 LOC + config)

- **16 never-called `GccApiClient` methods, HIGH confidence**: the entire route-offer +
  route-release client feature (7 methods, `GccApiClient.kt:274-283, 662-673` — zero UI
  references), plus `forceCloseBounty`, `forceJobStatus`, `getAreaCleanliness`, `getScan`,
  `getSpotVerifications`, `getSpotsNeedingVerification`, `getVerificationStatus`,
  `createSpotReport`, `retractCoverageEvents`.
- **7 unused shared DTO enums**: `EnterpriseTier`, `SponsorPackageStatus`,
  `PendingAddressStatus`, `WaitlistStatus`, `UserRole`, `RouteSessionStatus`,
  `RouteMarkerType`.
- **3 dead navigation functions** in `RootComponent.kt:87,99,147` — note
  `navigateToAdminPanel()` holds the only client-side `isAdmin()` gate and every call site
  bypasses it via `switchToTab(Screen.AdminPanel)` (server still enforces role; the client
  gate is dead).
- Unused resource `chicago_community_areas.geojson`; stale signing artifacts
  (`iosApp/4YDR3MPNFP.cer`, `AppStore_….mobileprovision`) should not live in git.

---

## 4. Patterns and quality — site/

- **Validation:** zod is used in 3 of 184 routes; everything else hand-checks or trusts
  `as`-cast bodies. Adopt one zod schema per route body, starting with checkout + auth.
- **Response shapes:** four coexisting conventions; `lib/api-helpers.ts` exists but is used
  by 2 routes. Standardize; stop leaking `debug:`/`code:` internals
  (`sponsors/me/route.ts:104`, `sponsors/register/route.ts:207`).
- **Duplication:** sponsor cookie-auth block copy-pasted 7× (forum/feedback routes) — extract
  `requireSponsor()`; admin Bearer-header fetch hand-rolled 14× — extract `adminFetch()`;
  `NEXT_PUBLIC_APP_URL` read directly ~15× with **three different fallbacks**
  (`globalcommunitycleaners.org`, `gcc.app`, `localhost:7300`) — route all through
  `appUrl()` in `lib/env.ts` per the repo's own convention.
- **Client-component overuse:** 47 of 74 pages are `'use client'`; exactly one `loading.tsx`
  in the app. Convert read-only dashboards to server components with Suspense.
- **Raw SQL bypassing Drizzle** in 8 files (21 `db.execute(sql…)` sites) with `as any[]`
  rows — undermines the schema-ownership policy; use the query builder.
- **Security notes:** admin token in `localStorage` (XSS-exfiltratable; an httpOnly-cookie
  path already exists in the same login route); no rate limiting on any auth/code endpoint;
  90-day JWTs with no revocation; admin token minted with `worker_id = worker?.id ??
  sponsor?.id` (`universal-login/route.ts:135`) — identity confusion for sponsor-only
  admins.
- Positives: Stripe idempotency-key coverage is 100% (all three
  `checkout.sessions.create` sites), webhook signature verification correct, 40/41 admin
  routes auth-gated.

## 5. Patterns and quality — mobile/shared

- **DTO drift (violates CLAUDE.md rule):** `signup_rankings_country` exists in `schema.ts:173`
  and server `IdentityTables.kt:73` but `SignupRankings` (`shared/.../Worker.kt:11-17`) has
  no `country` field. Add it.
- **`ApiResponse<T>.data` is non-nullable** — any 2xx with `data: null` throws at decode.
  Latent trap; make it nullable or guarantee the contract server-side.
- **Recomposition hazards:** `koinInject` + `collectAsState` inside `when` branches of
  `App.kt` (`:82, :230, :287`); geographic sort over all routes re-runs every recomposition
  (`App.kt:86-91`) — hoist and `remember(dashState.routes)`.
- **Screen files are huge** (`WalletScreen.kt` 1,682 LOC) — extract into the existing
  `ui/components` primitives.
- Android `createSecureStorage()` silently falls back to plaintext `Settings()` when context
  is null (`androidMain/SecureStorage.kt:47`) — with the raw-password storage above, that
  path must fail closed.
- CLAUDE.md doc lag: `Tables.kt` is already split into `RouteTables`/`FinanceTables`/
  `EngagementTables`/… but the doc says only `IdentityTables.kt` exists.

---

## 6. Prioritized master list

| # | Fix | Area | Value / Effort |
|---|---|---|---|
| 1 | Private storage for verification documents | mobile+site | Critical / S |
| 2 | Stripe webhook idempotency (event guard + unique index) | site | Critical / S |
| 3 | Atomic SQL increments for stats/money counters | site | High / S |
| 4 | `aps-environment=production` for release builds | iosApp | High / XS |
| 5 | Register `gcc://` scheme + `www.` applinks | iosApp | High / XS |
| 6 | HTTP timeouts + bounded retry in `GccApiClient` | shared | High / S |
| 7 | Decide Ktor-vs-Next backend story; document; fix or retire Ktor login | server | High / M |
| 8 | Neon-serverless driver for transactional flows | site | High / M |
| 9 | `scope.cancel()` in `RouteScanViewModel.onCleared()` | composeApp | Med / XS |
| 10 | Stop persisting raw password for biometrics; `…ThisDeviceOnly` | shared | Med / S |
| 11 | Per-environment base-URL config; kill 6 hardcoded hosts | shared | Med / S |
| 12 | Remove `token!!` force-unwraps in login/biometric VMs | composeApp | Med / XS |
| 13 | Delete CERTAIN dead code (26 components, dead routes, deps, CSS) | site | Med / M |
| 14 | Delete dead API-client methods + DTO enums; add `SignupRankings.country` | shared | Med / S |
| 15 | `requireSponsor()` / `adminFetch()` / `appUrl()` consolidation | site | Med / S |
| 16 | zod validation on checkout + auth routes, expand outward | site | Med / M |
| 17 | Rate limiting on auth/code endpoints | site | Med / M |
| 18 | Admin auth off localStorage onto httpOnly cookie | site | Med / M |
