# Neon operational efficiency

## Dated planning baseline (2026-08-08)

These are planning-time observations, not guarantees. The GCC Neon project is
`icy-sun-66022287`, using production branch `br-lively-recipe-aefttxnd` and
primary endpoint `ep-long-feather-aebr43g4`. Its observed compute envelope was
1–4 CU with a 300-second suspend setting. Month-to-date usage was approximately
181 CU-hours and $19.18, with a projected baseline near $74/month.

## Seven-day scorecard

Track the following daily for seven days under comparable traffic:

- Neon CU-hours/day.
- Active compute hours/day.
- Data transfer/egress.
- API p50 and p95 latency.
- Error rate.
- Cron duration, rows scanned, and rows processed.
- Affected-area request rate.
- Mobile polling request rate.

## Release gates

Do not release an efficiency change unless all of these gates hold:

- No API contract regression.
- No increase greater than 10% in p95 for uncached writes.
- No stale safety-critical map delta beyond its documented cursor flow.
- No missed scheduled release.
- Target GCC Neon cost of $15–25/month under comparable traffic.

## Route-to-cache-family matrix (Task 4)

Authoritative writers invalidate only the families their committed write
changes. Compatibility kinds (`cleanup-impact`, `worker-verification`,
`sponsorship`, `supporter`, `affected-area`) remain available; prefer the
granular kinds (`public-stats`, `impact-stats`, `wall-presentation`) when a
write touches a single projection.

| Authoritative writer (success path) | Families invalidated | Change kind(s) |
| --- | --- | --- |
| `stripe/webhooks` completed block sponsorship | public, impact, walls, affected-area snapshots | `sponsorship` + `affected-area` |
| `stripe/webhooks` completed general donation | public, impact, walls | `sponsorship` |
| `stripe/webhooks` payout worker-stat update | public + impact (compat) | `cleanup-impact` |
| `jobs/[id]/complete` | impact only | `impact-stats` |
| `admin/workers/[id]/verify` (status change, non-empty RETURNING) | public + impact | `worker-verification` |
| `community-reports` create | public only | `public-stats` |
| `waitlist/join` | public + impact | `supporter` |
| `sponsors/checkout` / `register` / offline sponsor create | public + impact | `supporter` |
| `sponsors/settings` anonymity/presentation | walls only | `wall-presentation` |
| `admin/block-proposals/[id]/approve` (available + boundary) | impact + snapshots | `impact-stats`, `affected-area` |
| `admin/blocks/[blockId]/boundaries` | snapshots only | `affected-area` |
| `admin/routes/accept` funded blocks with geometry | public + impact + snapshots | `public-stats`, `impact-stats`, `affected-area` |
| `admin/routes` funded block, no boundary | public + impact | `public-stats`, `impact-stats` |
| `admin/routes/[routeId]/assign` create/cancel job | impact only | `impact-stats` |
| `community-reports/[id]/thank`, `waitlist/profile`, `waitlist/update` | none | — |

Rules:

- Invalidate only after the durable mutation resolves successfully.
- Zero-row `UPDATE … RETURNING` / empty results skip invalidation.
- Stripe duplicate event deliveries and signature/validation failures invalidate nothing.
- Real mobile delta routes (`/api/map/coverage`, `/api/map/observations`) are never cached or invalidated here.
- Finite loader TTLs (5m stats / 30–60s snapshots / 30m walls) bound any residual staleness for out-of-scope admin writers.

## Index audit (Task 7) — 2026-08-08

Production-shaped `EXPLAIN (ANALYZE, BUFFERS)` was not executed against the
live Neon branch in this worktree session (operator gate: no production query
instrumentation without confirmation). Existing indexes already cover the
primary efficiency predicates:

| Query family | Existing index | Notes |
| --- | --- | --- |
| Due route batches | `idx_route_release_batches_status_next_wave` on `(status, nextWaveAt)` | Used by Task 6 due filter; no duplicate added. |
| Offer notification drain | `idx_route_offer_notifications_status_scheduled` | Pre-existing; left unchanged. |
| Affected-area snapshots | service-area / status filters on blocks | Snapshot TTL + tag invalidation bound read pressure; no new index without measured seq-scan evidence. |
| Public/impact aggregates | full-table counts on small dimension tables | Caching (Task 2) is the primary reduction; indexes deferred until EXPLAIN shows material sequential cost at production cardinality. |

**Decision:** no migration `0041_neon_efficiency_indexes.sql` in this rollout.
Re-open if seven-day scorecard shows residual sequential scans after caching
and cron bounds are live.

## Neon compute canary (Task 8) — executed 2026-08-09

Operator confirmation: **yes** (user requested canary after production code deploy).

| Field | Value |
| --- | --- |
| Project | `icy-sun-66022287` (Global Community Cleaners) |
| Branch | `br-lively-recipe-aefttxnd` (production) |
| Endpoint | `ep-long-feather-aebr43g4` |
| Pre-change compute | autoscaling **1–4 CU** |
| Pre-change suspend | **300s** |
| Post-change compute | autoscaling **0.25–1 CU** |
| Post-change suspend | **300s** (unchanged) |
| Applied at (UTC) | **2026-08-09T03:43:20Z** |
| Operator | Blake (via agent) |
| Method | Neon API `PATCH /projects/.../endpoints/...` |
| Immediate smoke | `GET /api/public/stats` → 200, `Cache-Control: public, max-age=60` |
| Connection URI / password | **not changed** |

### 48-hour observation window

Start: **2026-08-09T03:43Z**. End: **2026-08-11T03:43Z**.

Check at least every four hours during active periods: p95 API latency, 5xx
rate, connection errors, compute saturation, cold-start latency, cron
completion, mobile map freshness.

### Rollback procedure

Restore pre-canary envelope only (no credential/URI changes):

```text
autoscaling_limit_min_cu = 1
autoscaling_limit_max_cu = 4
suspend_timeout_seconds = 300
```

Rollback immediately if:

- p95 regresses >20% for 15 minutes
- error rate exceeds 1%
- compute remains saturated for 10 minutes
- cron misses its release window
- mobile deltas violate the freshness gate

Status: **canary applied; observation in progress.**

## Seven-day decision template (Task 9)

Code deployed to production Vercel (`fa193694`) and compute canary applied.
After **seven complete days** from 2026-08-09 (and after the 48h canary
observation), record one of:

- retain settings
- tune cache/poll intervals
- roll back compute
- investigate replica (only if primary CPU/read I/O remains dominant after
  caching/polling and always-on replica compute is cheaper)

Target projected GCC Neon cost: **$15–25/month** under comparable traffic.
Combined GCC + PlayOnPhone target: **$25–40/month** including Launch minimums.

Deploy checklist:

- [x] Efficiency code merged to `main` and pushed
- [x] Vercel production READY (`fa193694`)
- [x] Public stats smoke (cache headers present)
- [x] Neon canary applied (0.25–1 CU, 300s suspend)
- [ ] 48h canary observation complete
- [ ] Seven-day scorecard complete
- [ ] Final retain / tune / rollback / replica decision recorded

Status: **deployed + canary live; monitoring open.**
