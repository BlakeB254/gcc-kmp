# Cron & scheduling

**Status:** authoritative for how scheduled work runs. Companion to
`ARCHITECTURE-and-source-of-truth.md`.

## What changed and why

Nothing about the production topology moved. The website, the iOS app, and the
Android app are all still served by the Next.js app on Vercel
(`api.globalcommunitycleaners.org`), reading Neon through Drizzle. Render does
**not** serve traffic and holds **no** business logic.

Render exists here for exactly one reason: **Vercel Hobby cannot schedule.**

- Hobby caps cron jobs, and a `vercel.json` entry more frequent than once a day
  breaks every deploy on the project.
- Consequently `/api/cron/route-releases` ran once daily, at `0 13 * * *`.
  `site/src/lib/route-queue.ts` says what that costs: *"a daily cron means
  workers learn about a wave at most once a day."*
- Because a second cron entry was unsafe, `ingestBuildingFootprints()` was
  piggybacked onto the route-releases handler. That handler declared no
  `maxDuration`, so it ran at Vercel's ~10s default — while a full Overpass
  sweep needs ~48s. **The piggybacked ingest was being killed every day**, inside
  a non-fatal `try/catch` that swallowed the timeout.

The route-releases handler had already named the fix in a comment: *"for
production use a frequent schedule … or an external scheduler hitting this
endpoint on Hobby."* That is what `render.yaml` provides.

## Topology

```
Render Cron  ──curl + Bearer CRON_SECRET──▶  Next.js /api/cron/*  ──Drizzle──▶  Neon
(schedule only)                              (all the logic)
```

| Job | Schedule (UTC) | Calls |
|---|---|---|
| `gcc-cron-route-releases` | `*/15 * * * *` | `/api/cron/route-releases` |
| `gcc-cron-building-footprints` | `23 * * * *` | `/api/cron/building-footprints` |

The daily `vercel.json` cron is **retained as a backstop**. A double-fire is a
no-op, not a double-issue of offers — see below.

## The lease (why frequent cron is safe)

`processDueBatches()` has no locking. At once-a-day that was fine. At every 15
minutes, a slow tick can still be running when the next starts, and two
overlapping runs would issue duplicate offers and duplicate notification rows.

The `neon-http` driver sends every query as an independent stateless HTTP
request and has no `db.transaction` support, so `pg_advisory_lock` (session-
scoped) is unusable. Instead `site/src/lib/cron-lease.ts` uses a `cron_leases`
row acquired with one atomic statement:

```sql
insert into cron_leases (name, locked_at, expires_at)
values ($1, now(), now() + make_interval(secs => $2::int))
on conflict (name) do update
  set locked_at = now(), expires_at = now() + make_interval(secs => $2::int)
  where cron_leases.expires_at < now()
returning name
```

Postgres evaluates the conflict target and the predicate together, so exactly
one caller gets a `RETURNING` row. The `::int` casts are load-bearing — an
untyped bind param won't resolve the `make_interval` overload.

`expires_at` is a TTL rather than a boolean so a crashed run self-heals after
`ttlSeconds` instead of wedging the queue forever. **Invariant: the lease TTL
must exceed the handler's `maxDuration`,** or a long run loses its lease
mid-flight. Today both are TTL 120s against `maxDuration = 60`.

A held lease returns HTTP 200 with `{"skipped":"lease-held"}`. That is expected
at a 15m cadence and must not be treated as a failure.

## One-time setup (requires a browser; cannot be automated)

1. **Render → Account Settings → API Keys** → create a key. Needed only for the
   Render CLI/MCP, not for the blueprint itself.
2. **Render → Account Settings → Git Providers** → connect GitHub, authorize the
   `gcc` repository. Render cannot build from the repo until this OAuth grant
   exists — no tool can do it for you.
3. **Render → New → Blueprint** → pick this repo. It reads `render.yaml` and
   creates both cron jobs.
4. Set `CRON_SECRET` on **both** services to the exact value already set on the
   Vercel project (`Vercel → Project → Settings → Environment Variables`). It is
   declared `sync: false`, so it is never stored in git.
5. Apply the `cron_leases` migration to Neon before the first Render tick:
   `cd site && npm run db:push` (canonical here — not `drizzle-kit migrate`;
   see root `CLAUDE.md`). `0033_add_cron_leases.sql` is `IF NOT EXISTS`-guarded
   and safe to re-apply.
6. Trigger each job once from the Render dashboard and confirm `HTTP 200`.

## Verifying

```bash
# Should return 401 — proves the endpoint is guarded.
curl -s -o /dev/null -w '%{http_code}\n' \
  https://api.globalcommunitycleaners.org/api/cron/route-releases

# Should return 200 with a JSON body.
curl -s -H "Authorization: Bearer $CRON_SECRET" \
  https://api.globalcommunitycleaners.org/api/cron/route-releases
```

## Follow-ups

- Once the Render footprints job is proven green, delete the daily
  `vercel.json` cron entry. Keeping it costs nothing (the lease absorbs it) but
  it is now redundant.
- `/api/admin/newsletter/send` still does a sequential send loop inline with
  `maxDuration = 300`, which exceeds the Hobby 60s ceiling. It is the next
  candidate to move behind a cron endpoint + Render schedule.
- If Overpass ingestion outgrows the 60s Vercel ceiling, that single job — a
  narrow, one-table writer — is the only piece worth moving to a real Ktor
  worker on Render. Do not move the release queue: it would create a second
  implementation against the same tables. See root `CLAUDE.md` on schema
  ownership, and `server/README.md`.
