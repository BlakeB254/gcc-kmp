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
| `gcc-cron-verification-docs` (ms-a2 user timer) | daily `04:37` | `/api/cron/verification-docs?retention_days=90&retention=enforce` |
| `gcc-cron-scan-index` (ms-a2 user timer) | every 30 min, `:11`/`:41` | `/api/cron/scan-index` |
| `gcc-cron-civic-sync` (ms-a2 user timer) | every 6 h at `:47` | `/api/cron/civic-sync` |
| `gcc-cron-scan-watchdog` (ms-a2 user timer) | every 6 h at `:53` | `/api/cron/scan-watchdog` |

**Verification documents** (`/api/cron/verification-docs`). Government IDs and
proofs of address are PII, kept privately in R2 under `verification/{workerId}/`.
The scheduler owns the policy through query parameters, so a bare call changes
nothing:

- `retention_days=N&retention=dry|enforce` purges objects older than N days
  (never fewer than 30) whose worker is not in review: verification pending or
  needs-info, a document pending or resubmit, or an address change pending.
  Referenced media rows are tombstoned to `purged:<key>` before the object is
  deleted. The policy is **90 days**, set 2026-10-17. Change it in the timer's
  unit, not in code.
- `migrate=dry|apply&limit=N` was the one-time move of legacy *public* Blob
  documents (`worker-docs/`, `government-id/`, `proof-of-address/`,
  `workers/*/address-change/`) into R2. It copies each file, verifies size and
  MD5, repoints the media row, and only then deletes the public copy. It is
  kept for re-runs and is a no-op once nothing is left.

`backend/…/VerificationPhotoRetention.kt` was never deployed. This route is the
retention job. The rest of `backend/` is superseded the same way (see
`backend/README.md`).

**Which scheduler actually runs.** As of 2026-10-18 the jobs that run are the
ms-a2 user timers (`journalctl --user -u gcc-cron-*` shows each tick and its
JSON). Their unit files are versioned in `ops/systemd/ms-a2/` (secret in
`~/.config/cdx/gcc-cron.env`, not in git). There is no evidence the Render
blueprint below was ever created; treat it as the documented alternative, not
as what is live.

**Scan indexing and the 311 mirror** (`/api/cron/scan-index`,
`/api/cron/civic-sync`, code in `site/src/lib/scan-index/`). These replace the
Kotlin `backend/` sweeps (`ScanIndexer`, `CivicReportSync`), which ran on no
host after 2026-09-08 — so no scan was indexed or queued for reconstruction,
and `civic_reports` froze. Same rows, same `'0:0'` cell fallback, same enqueue
rule. **Upload grants** tie scans to workers: both upload-url routes record
every presigned raw/ key against the authenticated worker in
`capture_upload_grants` (migration 0054). The first worker granted a key
under a session folder owns that session (`capture_namespace_owners`); every
other worker is refused any key in it — with **503 + Retry-After**, not a 4xx:
on a shared test handset that is another account's pending session, and the
app keeps a file it gets a 5xx for (it deletes chunks on any 4xx). Device
folders are not bound, so there only the exact key is owned (keys carry the
session id, so a 409 there is a real collision). A first grant over an object
that already exists is refused (legacy data can't be claimed) unless it sits
in the requester's own session folder; if R2 can't say whether the key is
free, the answer is 503 and nothing is granted. A grant counts only for an object written inside its window (issued_at −
15 min … last_issued_at + 30 min URL TTL + 15 min); a grant whose URL expired
with no object behind it can be re-granted. Legacy sessions are frozen: an
object added after the cutover needs a grant from the session's one grant
owner.
Manifests written from the first grant + 2h on are accepted only if their key
was granted, keep only keys granted to that same worker (device folder for
photos/USDZ/manifest, session folder for capture chunks, event log and
`photos/` keyframes), and stamp `scan_sessions.worker_id`. Older manifests keep
the folder rules alone. Reads are capped at fetch time (ranged GET: manifest
4 MiB, event log 2 MiB, inflated log 64 MiB). Each run fits Vercel's 60s: the indexer only re-reads manifests whose
session changed since it was indexed (the tables are the watermark), and the
311 sync resumes from `MAX(last_modified_date)`. Both are leased.

**Silence alarm** (`/api/cron/scan-watchdog`). Answers 503 — failing the
timer's `curl -f` — when a manifest has sat unindexed longer than
`scan_hours`, the newest mirrored 311 change is older than `civic_hours`, or a
reconstruction job has been claimable for longer than `recon_hours`. With
`CRON_ALERT_EMAIL` set on Vercel it also emails. A manifest the indexer can never accept
(undecodable, missing a required field, a key that doesn't match its ids) is
recorded in `scan_manifest_rejects` (migration 0052): the indexer stops
re-reading it until it is re-uploaded, and the watchdog fails **once** for it —
the run that reports it marks it alerted — and leaves it out of the
"unindexed" check afterwards. Five debug/test uploads in prod R2 are in this
state; deleting them is the owner's call. It exists because the Spark
recon timer kept logging "queue empty" for six weeks while nothing was being
enqueued.

The route-releases tick also sweeps abandoned **$0 test jobs** back off their
worker (claimed and not started within 60 min, or in progress for over 24h),
using `releaseJobPatch` — rules in `site/src/lib/stale-test-jobs.ts`, mirrored
in `shared/.../model/Job.kt`. Stale **paid** jobs are left alone and only
counted in the response (`staleJobs.paidStale`) until the owner decides a policy.

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
