# GCC (Global Community Cleaners) — repo guide

A community-cleanup marketplace. One repo, multiple targets:

- `site/` — Next.js 16 (App Router) web + admin + Stripe checkout + Drizzle ORM
- `shared/` — Kotlin Multiplatform DTOs + API client used by the mobile app
- `composeApp/` — Compose Multiplatform UI (Android, iOS, Desktop)
- `iosApp/` — Xcode wrapper; only builds on the MacBook via fastlane / `cdx-mobile`

## The backend is the Next.js API routes

**`site/src/app/api/` is the one and only backend.** Both mobile platforms
hardcode `https://api.globalcommunitycleaners.org` (`PlatformConfig.kt` in
`androidMain`/`iosMain`), and `site/src/middleware.ts` shows that subdomain is
served by Next.js (passes `/api/*` through, 301s everything else to `www`).
Every client call in `GccApiClient.kt` has a matching route under
`site/src/app/api/` — new mobile endpoints go there.

There is no `server/` module: the former Ktor backend's source is deleted and
`include(":server")` is gone from `settings.gradle.kts`. A `server/` directory
on disk is untracked, gitignored build output — safe to remove, never a place
to edit.

## Schema ownership policy (read this before changing data models)

**Drizzle is the authoritative source of database schema.** Everything else is a
mirror.

- `site/src/db/schema.ts` is the only place that declares tables, columns,
  enums, and relations.
- `npx drizzle-kit generate` produces SQL migrations under
  `site/src/db/migrations/`. Those migrations are what actually run against
  Neon Postgres.
- `shared/src/commonMain/kotlin/com/gcc/shared/model/*.kt` data classes are
  the API DTO mirror. They must align with the shapes the `site/` API routes
  send to and accept from clients.

**Rule:** any change to `schema.ts` that alters a shape a client sees must,
in the same PR, update the matching shared `@Serializable` model. There is no
automated guard for this — it is a discipline thing.

### How migrations actually get to production

**`drizzle-kit push` is the canonical deploy mechanism, not
`drizzle-kit migrate`.** The team has been editing `schema.ts` and
running `npm run db:push`, which diffs the live schema against
`schema.ts` and applies the necessary DDL. The migration `.sql` files
under `site/src/db/migrations/` accumulate as a side-effect of
`drizzle-kit generate` but have historically not been the deployment
path.

Symptoms of this divergence:
- The `drizzle.__drizzle_migrations` tracking table was empty until
  2026-05-23 when 0017/0018/0019 were applied via direct `psql` and
  manually inserted. It still doesn't reflect 0000–0016.
- Duplicate migration numbers exist right now — three pairs:
  0013 (`0013_add_admin_grant_points_action.sql` /
  `0013_add_in_kind_donations.sql`), 0032 (`0032_add_building_footprints.sql`
  / `0032_drop_checkpoints.sql`), 0033 (`0033_add_cron_leases.sql` /
  `0033_add_scan_session_index.sql`). Parallel `drizzle-kit generate` runs
  keep minting the same next number. There is also no .sql at 0010 (an
  earlier collision cleanup moved both 0010 files forward). The three
  surfaces disagree: 46 `.sql` files vs 31 `meta/_journal.json` entries vs
  14 `meta/` snapshots.
- `meta/_journal.json` references `0007_regular_shotgun` but the .sql
  file is `0007_add_community_reports.sql` (tag/file mismatch).
- The snapshot chain is coherent only through 0015. Beyond that the
  cumulative schema state isn't captured anywhere drizzle-kit can read.
- `drizzle-kit generate` can run after the 2026-05-23 chain fix
  (0013 prevId re-pointed at 0009 id), but it produces nonsense
  migrations because it treats everything since 0015 as net-new.

**What you can safely do today:**
- Edit `schema.ts` freely. `npm run db:push` against the prod DB will
  reconcile it. Idempotent.
- Hand-write SQL migration files with `CREATE … IF NOT EXISTS` guards
  when you need a recorded artifact (e.g., for indexes you want to
  apply identically across environments).
- Apply hand-written migrations via `psql` and insert a row into
  `drizzle.__drizzle_migrations` so the tracking table stays current.

**What needs a dedicated repair session:**
- Rebuild snapshots 0016–0019 by walking the cumulative schema state
  through each migration, OR run `drizzle-kit pull` against a known-
  good DB to regenerate from scratch (will overwrite `schema.ts`;
  diff carefully first).
- Resolve the duplicate-numbered .sql files (decide which version is
  canonical and rename/delete the other).
- Add journal entries for 0010-0012 and 0017-0019 with correct prevId
  chaining and matching meta/ snapshots.
- Until this is done, **don't trust the output of `drizzle-kit
  generate`** — verify the SQL by hand or fall back to `db:push`.

## Other conventions

- **JWT_SECRET is required at startup.** Next.js fails to boot if it's
  unset. No "dev fallback" — set it in `site/.env.local`.
- **Database URL** points at the Neon database (`site/.env.local`). Don't
  introduce a separate DB — see schema-ownership policy.
- **Stripe checkout endpoints** use deterministic idempotency keys
  (`lib/stripe-idempotency.ts`). Don't call `stripe.checkout.sessions.create`
  without a key.
- **Public env var fallbacks** go through `site/src/lib/env.ts` so missing
  config fails fast in production instead of silently hitting localhost.
- **Scheduled work is driven from Render, not `vercel.json`.** Vercel Hobby
  caps crons at one per day and a more-frequent entry breaks all deploys, so
  `render.yaml` defines cron jobs that `curl` the `CRON_SECRET`-guarded
  `/api/cron/*` endpoints. Render serves no traffic and holds no logic — only
  the schedule. Any handler reachable more often than daily must be wrapped in
  `withCronLease()` (`site/src/lib/cron-lease.ts`); `processDueBatches()` has
  no locking of its own. **The lease TTL must exceed the route's
  `maxDuration`.** See `docs/governance/cron-and-scheduling.md`.
- **CI runs** the Next.js site (typecheck + build) and Kotlin (`:shared`
  JVM + tests, `:composeApp` desktop compile + `desktopTest`) on every PR
  and main push. Android `assembleDebug` runs only on `workflow_dispatch`.
  iOS builds happen on the MacBook via `cdx-mobile`, not in GitHub Actions.

## Working across the two machines (DGX + MacBook)

The DGX (this machine, Linux) handles Next.js and the shared/ Kotlin
code that compiles on Linux. The MacBook (`ssh macbook`) handles
iOS builds and the Android side. They share GitHub as the source of
truth.

- `cdx-mobile status` — sync state across devices
- `cdx-mobile sync` — DGX → GitHub → MacBook
- `cdx-mobile build ios|android|both` — build on the MacBook
- `cdx-mobile fetch` — pull artifacts back

If the MacBook has uncommitted local work and you need to land DGX-side
changes, snapshot the MacBook work to a `wip/...` branch before pulling.

## Common commands

```bash
# DGX
cd site && npm run dev                                # Next.js on :7350
cd site && npx tsc --noEmit                            # typecheck
./gradlew :composeApp:desktopTest                      # scan/coverage math suite

# MacBook (via SSH)
cdx-mobile build ios --sync                            # full iOS build
cdx-mobile build android                               # full Android build
```

## Where to read for context

**Start here — the source-of-truth set:**
- `docs/governance/00-INDEX.md` — the governing documentation set (architecture,
  what actually runs in prod, the production API reference, the mobile app map).
  **This is the primary architecture entry point; read the index first.**
- `docs/governance/scan-and-coverage-model.md` — how the scan / coverage system
  actually works end-to-end (SEEN/GRAZED/CLOSE tiers, raycast fan, organic
  marching-squares rendering, the fog veil, the indoor gate, gridCell keying,
  the coverage/retraction queues, world-observation supersession, cell→points).
  A living map that points into the code — read this before touching scanning.
- `docs/CONVENTIONS.md` — the cross-cutting client↔server contracts that MUST
  stay in lockstep (Coordinates `(lng,lat)` ordering, gridCell byte-equality,
  the litter-footprint mirror).

**Feature history / in-flight (point-in-time, may be stale):**
- `docs/feature-spec-2026-05.md` — product canon (§A invariants) + methods (§B)
- `docs/plans/` and `docs/superpowers/{specs,plans}/` — dated change proposals;
  a shipped plan is NOT current truth — cross-check against `docs/governance/`.
- The GitHub PR list at `BlakeB254/gcc` — what's actively shipping
