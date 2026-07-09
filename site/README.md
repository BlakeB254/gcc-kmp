# site — Next.js 16 web + the production API

**This is production.** `site/` serves the website AND `api.globalcommunitycleaners.org` — every endpoint the mobile app calls (`api/*`). The Ktor `server/` is NOT deployed (see root `CLAUDE.md` and `docs/governance/ARCHITECTURE-and-source-of-truth.md`). Next.js App Router + Drizzle ORM over Neon Postgres, deployed on Vercel.

## Layout
- `src/app/` — App Router. `src/app/api/**` = the mobile + admin API surface (grouped in `docs/governance/API-reference-production.md`).
- `src/db/` — **`schema.ts` is the authoritative DB schema** (everything else mirrors it). `migrations/` are hand-written and applied via `psql` — `drizzle-kit generate`/`db:push` are NOT trusted (see `CLAUDE.md` for the full migration reality + failure history).
- `src/lib/` — server logic: `scan-coverage.ts` (coverage rollup + points), `world-observations.ts` (`gridCell`, must byte-match the client), `points-config.ts` (the real points economy), `block-completion-report.ts`, `litter-footprints.ts`, `stripe/`.

## Conventions
- **neon-http has NO `db.transaction`** — use `db.batch([...])` (one round-trip) or sequential awaits.
- Any `schema.ts` change updates the Ktor `Tables.kt` mirror + shared DTOs in the same PR.
- `gridCell`, `Coordinates(lng,lat)`, and the litter catalog must stay in lockstep with the client — see `docs/CONVENTIONS.md`.
- Vercel Hobby plan: any cron more frequent than daily makes ALL deploys fail; long serverless work (e.g. synchronous Overpass fetch) can exceed the function timeout.

## Dev
- `cd site && npm run dev` (:7350) · `npx tsc --noEmit` (baseline has pre-existing `@turf`/`@aws-sdk` resolution errors — CI is clean).
- Tests use Node's built-in runner: `DATABASE_URL=postgres://test:test@localhost/test npx tsx --test <file>` (not vitest/jest). Test the pure exports (no DB).
- Fresh worktrees need `node_modules` + gitignored `.env.local` from the main checkout before `@/db` resolves.
