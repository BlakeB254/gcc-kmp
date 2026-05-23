# GCC (Global Community Cleaners) — repo guide

A community-cleanup marketplace. One repo, multiple targets:

- `site/` — Next.js 16 (App Router) web + admin + Stripe checkout + Drizzle ORM
- `server/` — Ktor (Netty) JVM backend, port 7310, JWT auth, Exposed over Postgres
- `shared/` — Kotlin Multiplatform DTOs + API client used by mobile and server
- `composeApp/` — Compose Multiplatform UI (Android, iOS, Desktop)
- `iosApp/` — Xcode wrapper; only builds on the MacBook via fastlane / `cdx-mobile`

## Schema ownership policy (read this before changing data models)

**Drizzle is the authoritative source of database schema.** Everything else is a
mirror.

- `site/src/db/schema.ts` is the only place that declares tables, columns,
  enums, and relations.
- `npx drizzle-kit generate` produces SQL migrations under
  `site/src/db/migrations/`. Those migrations are what actually run against
  Neon Postgres.
- `server/src/main/kotlin/com/gcc/server/db/tables/Tables.kt` is a **hand-
  maintained Kotlin mirror** that Exposed uses to compile SQL queries. It
  does *not* create the schema. The Ktor server's `DatabaseFactory` opens
  a connection; it does not migrate.
- `shared/src/commonMain/kotlin/com/gcc/shared/model/*.kt` data classes are
  the API DTO mirror. They must align with the columns the Ktor server
  exposes.

**Rule:** any change to `schema.ts` must, in the same PR, update
`Tables.kt` (every column the server reads or writes) and any shared
`@Serializable` model that exposes the changed shape to clients.

There is no automated guard for this yet (planned: a Gradle task that
introspects `information_schema` and fails if a table is missing from
`Tables.kt`). Until then, this is a discipline thing. The route-queue
subsystem (merged from `wip/route-queue-and-scans-20260523`) is a good
example of doing it right — three new tables in migrations 0017/0018
added with matching Exposed table objects and a shared `RouteQueue.kt`
DTO in the same commit.

`Tables.kt` is being split per-domain for navigability. So far:
- `IdentityTables.kt` — Workers, Sponsors, WaitlistEntries, MediaTable
- `Tables.kt` — everything else (still ~1000 LOC; will be split further
  as `RouteTables.kt`, `FinanceTables.kt`, `EngagementTables.kt`,
  `ModerationTables.kt`)

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
- (Resolved 2026-05-23) The migration filenames previously had
  collisions at 0008/0009/0010 from parallel `drizzle-kit generate`
  runs. The journal-canonical files (`0008_noisy_adam_destine.sql`,
  `0009_breezy_old_lace.sql`) kept their numbers; the orphan-named
  duplicates were renumbered forward to 0020-0023. Note this leaves
  no .sql at 0010 (both 0010 files moved forward); the orphan-applied
  state in prod is unchanged.
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

- **JWT_SECRET is required at startup.** Both Ktor and Next.js fail to boot
  if it's unset. No "dev fallback" — set it in `.env` (root, for the Ktor
  server) and `site/.env.local` (for Next.js). They must agree because
  tokens issued by one verify on the other.
- **Database URL** points at the same Neon database from both backends.
  Don't introduce a separate DB — see schema-ownership policy.
- **Stripe checkout endpoints** use deterministic idempotency keys
  (`lib/stripe-idempotency.ts`). Don't call `stripe.checkout.sessions.create`
  without a key.
- **Public env var fallbacks** go through `site/src/lib/env.ts` so missing
  config fails fast in production instead of silently hitting localhost.
- **CI runs** the Next.js site (typecheck + build) and Kotlin (`:shared`
  JVM, `:server` build with tests, `:composeApp` desktop compile) on every
  PR and main push. Android `assembleDebug` runs only on
  `workflow_dispatch`. iOS builds happen on the MacBook via `cdx-mobile`,
  not in GitHub Actions.

## Working across the two machines (DGX + MacBook)

The DGX (this machine, Linux) handles Next.js, Ktor JVM, and shared/
Kotlin code that compiles on Linux. The MacBook (`ssh macbook`) handles
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
./gradlew :server:run                                  # Ktor on :7310
./gradlew :server:test                                 # Ktor tests
cd site && npx tsc --noEmit                            # typecheck

# MacBook (via SSH)
cdx-mobile build ios --sync                            # full iOS build
cdx-mobile build android                               # full Android build
```

## Where to read for context

- `docs/feature-spec-2026-05.md` — current feature work
- `docs/plans/` — initiative briefs
- `docs/superpowers/` — designs in flight
- The GitHub PR list at `BlakeB254/gcc` — what's actively shipping
