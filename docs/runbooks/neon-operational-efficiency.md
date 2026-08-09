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
