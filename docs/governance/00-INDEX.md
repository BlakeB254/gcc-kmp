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

Companion docs already in `docs/` (feature-level, still valid):
- `feature-spec-2026-05.md` — the canon (§A) and methods (§B). The bedrock.
- `PRD_route-queue-and-scans.md` — deep spec for the queue + scan features (note: describes the **Ktor branch** implementation; see doc #1 for what's actually in prod).
- `TestFlight-runbook-route-queue-scans.md` — build/deploy steps.
- `Test-plan-cleaning-sequence.md` — QA plan for the cleaning flow.

**Last full audit:** 2026-06-24. Re-audit when the Ktor server is deployed or the queue endpoints land in the Next.js API.

## The one-paragraph truth

Production is **100% the Next.js app on Vercel** (`site/`), serving `api.globalcommunitycleaners.org` and the website. The **Kotlin Ktor server (`server/`) is NOT deployed** — it's a parallel implementation on the `wip/route-queue-and-scans` branch. The **mobile app's cleaning/scan flow and direct route assignment work against prod today**, but the sophisticated **community queue / batch "next-in-line" onboarding exists only in the Ktor server and the app client — the production Next.js API does not implement it** (the DB tables exist via migration `0018`, but there are no endpoints). That gap is the single thing standing between "test the cleaning flow" (ready now) and "run a real 10-block session with automated ordered onboarding."
