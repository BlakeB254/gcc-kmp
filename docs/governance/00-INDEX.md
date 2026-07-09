# GCC Governing Documentation

This folder is the **source-of-truth documentation set** the GCC project is held to. Read in this order.

| # | Doc | What it governs |
|---|-----|-----------------|
| 1 | [ARCHITECTURE-and-source-of-truth.md](ARCHITECTURE-and-source-of-truth.md) | What actually runs in production, the Next.js-vs-Ktor truth, the database, CI/CD, and the client↔API parity gap. **Read first.** |
| 2 | [PRD-master.md](PRD-master.md) | The master product requirements: canon, the three product surfaces, feature status, and roadmap. |
| 3 | [gap-analysis-queue-onboarding.md](gap-analysis-queue-onboarding.md) | The one blocking decision for the two 10-block sessions: how to do "load next in line" when prod has no queue. |
| 4 | [API-reference-production.md](API-reference-production.md) | Every production endpoint (Next.js on Vercel), grouped, with auth. |
| 5 | [sitemap-website.md](sitemap-website.md) | Every website page route and audience. |
| 6 | [app-map-mobile.md](app-map-mobile.md) | Every mobile screen, the scan flow, and the endpoints the app calls. |
| 7 | [production-readiness-and-scaling.md](production-readiness-and-scaling.md) | Where compute actually happens (on-device vs. Vercel vs. DGX), what auto-scales, what doesn't (DGX splat processing, Stripe admin-approval throughput), and Cloudflare R2 setup steps for canonical scan storage. |
| 8 | [scan-and-coverage-model.md](scan-and-coverage-model.md) | **How the scan/coverage subsystem actually works** end-to-end: SEEN/GRAZED/CLOSE tiers, the raycast fan, organic marching-squares rendering, the fog veil + flashlight beam, the indoor footprint gate, gridCell keying, the coverage/retraction queues, and session→world-model→points. A living map that points into the code. Read before touching scanning. |

Companion docs already in `docs/` (feature-level, still valid):
- `feature-spec-2026-05.md` — the canon (§A) and methods (§B). The bedrock.
- `PRD_route-queue-and-scans.md` — deep spec for the queue + scan features (note: describes the **Ktor branch** implementation; see doc #1 for what's actually in prod).
- `TestFlight-runbook-route-queue-scans.md` — build/deploy steps.
- `Test-plan-cleaning-sequence.md` — QA plan for the cleaning flow.

**Last full audit:** 2026-06-24 (docs #1–7). Doc #8 (scan-and-coverage-model) added 2026-07-09 to cover the July subsystems — indoor-gate-v4, the scan-visibility rework (flashlight cone, quality-gated tiers, paint-the-town merged coverage), and the OSM building-footprint indoor gate — which post-date the 06-24 audit. Docs #1/#2 have not yet been re-dated for those; treat #8 as authoritative for scanning. Re-audit when the Ktor server is deployed or the queue endpoints land in the Next.js API.

## The one-paragraph truth

Production is **100% the Next.js app on Vercel** (`site/`), serving `api.globalcommunitycleaners.org` and the website. The **Kotlin Ktor server (`server/`) is NOT deployed** — it's a parallel implementation on the `wip/route-queue-and-scans` branch. The **mobile app's cleaning/scan flow and direct route assignment work against prod today**. The **community queue / batch "next-in-line" onboarding is now LIVE in the production Next.js API as of 2026-06-24** (Option B — ported from the Ktor `RouteQueueService` onto the migration-`0018` tables, deployed, and validated end-to-end). A Vercel Cron (`/api/cron/route-releases`) advances waves automatically. Remaining for a full 10-block session: more verified North Lawndale workers (only 5 eligible today) and the admin scan pass/fail override.
