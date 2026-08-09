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
