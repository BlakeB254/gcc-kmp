# GCC — System Architecture & Source of Truth

**Audited:** 2026-06-24 · **Status:** authoritative until the Ktor server is deployed or queue endpoints land in the Next.js API.

## 1. Production topology (what actually runs)

```
                    ┌─────────────────────────────┐
   iOS app  ───────▶│  api.globalcommunitycleaners│
 (Compose MP,       │        .org                 │
  bundle .worker)   │  = Next.js app on VERCEL    │◀──── Website (globalcommunitycleaners.org)
                    │  (site/) — App Router        │       same Next.js deployment
 Android app ──────▶│  ~115 /api/* route handlers │
                    └──────────────┬──────────────┘
                                   │ Drizzle ORM (neon serverless)
                                   ▼
                         ┌───────────────────┐
                         │  Neon PostgreSQL  │  (single shared DB)
                         └───────────────────┘
```

- **The website AND the mobile API are the same Vercel/Next.js deployment** (`site/`). The iOS/Android apps hardcode `https://api.globalcommunitycleaners.org` (`composeApp/src/iosMain/.../PlatformConfig.kt`), which is served by that Next.js app.
- **Database:** Neon Postgres via `DATABASE_URL`. The Next.js app uses **Drizzle ORM** (`site/src/db`), and Drizzle migrations (`site/src/db/migrations/`, 18+ files) are the **authoritative schema**.
- **Deploy:** Vercel Git integration auto-deploys `site/` from **`main`** on push. iOS ships via the Mac (launchd auto-pull of `main` → Xcode/fastlane → TestFlight/App Store). No GitHub Actions. `main` is authoritative; `*-wip-*` branches are snapshots.

## 2. The Ktor server is NOT in production

`server/` (Kotlin/Ktor + Exposed ORM) is a **parallel backend on the `wip/route-queue-and-scans` branch**. Evidence it is unused in prod:
- No Dockerfile / fly / railway / render / CI config anywhere; `Application.kt` binds `localhost:7310`.
- `scripts/setup-cloudflare-tunnel.sh` can expose a local Ktor as the api domain — that's a **dev/fallback** tool, not prod.
- The api domain returns Next.js responses (a `PUT …/publish` returned a Next.js 404 page; create/assign returned Next.js JSON).

**Implication:** any analysis or PRD written against `server/` describes a **possible future**, not current production. The earlier `docs/PRD_route-queue-and-scans.md` documents the Ktor implementation — accurate for the branch, but **not what serves users today**.

## 3. Two schemas, one database — and they diverge

Both `site/` (Drizzle) and `server/` (Exposed) model the same tables, but:
- The **Drizzle schema + migrations are the source of truth** (they run against Neon).
- The **Exposed tables in `server/` are stale/divergent** (type mismatches, no migration files). Do not treat `server/` table definitions as canonical.
- The **queue tables exist in prod** (`routeReleaseBatches`, `routeReleaseRoutes`, `routeJobOffers`, `routeCommunityQueue`, `routeOfferNotifications`) via migration `0018_add_route_release_queue.sql` — **but no Next.js endpoints use them** (scaffolding only).

## 4. Feature parity: prod (Next.js) vs. app client / Ktor branch

| Capability | In prod Next.js API? | In Ktor branch? | App client calls it? |
|---|---|---|---|
| Auth (email/Apple/Google), worker/sponsor | ✅ | ✅ | ✅ |
| Worker verification + admin review | ✅ | ✅ | ✅ |
| Routes CRUD, publish, **direct assign** (`isTest`) | ✅ | ✅ | ✅ |
| Worker **self-claim** available routes/jobs | ✅ | ✅ | ✅ |
| **Scan / clean flow** (`/api/scans/*`: phase, hotspots, coverage, complete) | ✅ | ✅ | ✅ |
| Spots/community reports + peer verification | ✅ | ✅ | ✅ |
| Bounties, feed, leaderboard, gamification, articles | ✅ | ✅ | ✅ |
| Stripe sponsor checkout + worker payouts (webhooks) | ✅ | partial | n/a |
| **Queue / batch "next-in-line" release & offers** (`/api/admin/route-releases`, `/api/workers/me/route-offers`) | ❌ **MISSING** | ✅ | ✅ (will 404 in prod) |
| Cron / wave processing / carryover | ❌ | ✅ | n/a |
| Admin: disputes, role change, bulk-verify, service-area create, some analytics variants | ⚠️ partial/uncertain | ✅ | ✅ (some will 404) |

### The client↔API parity gap (important governance risk)
The mobile app was built expecting the **Ktor** surface, so it calls endpoints that **may not exist in the prod Next.js API** (e.g., `/api/workers/me/route-offers`, `/api/admin/route-releases`, `/api/admin/disputes`, `/api/admin/workers/{id}/role`, `bulk-verify`). Those features will **silently fail (404)** on device. Treat the **production API reference (doc #4) as the contract**; anything the app calls that isn't there is a known gap, not a working feature.

## 5. What this means operationally

- **Cleaning sequence test (now):** ✅ Works against prod — scans + direct assignment exist. (Confirm `/api/scans` live during the on-device test.)
- **Two 10-block sessions with automated ordered onboarding:** ❌ Not possible on prod today — see [gap-analysis-queue-onboarding.md](gap-analysis-queue-onboarding.md). Options: build queue endpoints in Next.js, deploy the Ktor server, or run sessions via direct/self-claim with manual ordering.
- **Decision rule for any new feature:** decide **which backend owns it** (Next.js prod vs. Ktor-future) before building, and keep the app client in lockstep with the **deployed** API. Avoid shipping app calls to endpoints that don't exist in prod.

## 6. Canonical references
- Prod API contract: [API-reference-production.md](API-reference-production.md)
- Website pages: [sitemap-website.md](sitemap-website.md)
- App screens/flows: [app-map-mobile.md](app-map-mobile.md)
- Product canon: `../feature-spec-2026-05.md`
