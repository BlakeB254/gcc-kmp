# GCC — Master PRD

**Product:** Global Community Cleaners (GCC) · 501(c)(3) · Pilot: North Lawndale, Chicago
**Surfaces:** Website (Next.js/Vercel) · Worker mobile app (Compose Multiplatform; iOS App ID 6760203806) · Admin (web + in-app)
**Audited/owned:** 2026-06-24 · Supersedes feature-scoped PRDs for cross-cutting decisions; defers to `feature-spec-2026-05.md` for canon.

## 1. Vision & canon (load-bearing — from feature-spec §A)
GCC is the **field-evidence layer for community cleanliness** — not a cleaning service. It makes neighborhood cleanliness measurable, fundable, and accountable block-by-block.
- **A.2 Data quality:** Pokémon-Go-grade real-world capture — volumetric, georeferenced, timestamped, attested, hard to fake. (This is the digital-twin/game foundation.)
- **A.4 Unit of action:** one block, one resident, **$100**, before/after verified. 100% of the fee to the resident.
- **A.5 Hard to fake, easy to join:** live-capture attestation, GPS geofencing, peer verification; low-friction signup, capability-aware capture.
- **A.3 Global + verified; A.6 bounties; A.7 worker dignity (pay first); A.8 compounding data/impact.**

## 2. Users
Worker (resident cleaner), Sponsor (funds blocks), Admin/Coordinator (operates the system), Public (sees impact, donates, reports spots/bounties).

## 3. Product surfaces (see companion maps)
- **Website** — marketing, signup (worker/sponsor/waitlist), donate/sponsor checkout, bounties, impact/transparency, blog, community forum, **web worker job flow**, **admin console** (`/admin/routes`, `/admin/sponsors`, `/admin/newsletter`). Full list: `sitemap-website.md`.
- **Mobile app** — worker onboarding/verification, dashboard/map, route claim/assignment, **scout→clean scan flow**, spots, bounties, wallet, plus an **in-app admin panel**. Full list: `app-map-mobile.md`.
- **API** — single Next.js backend; contract in `API-reference-production.md`.

## 4. Feature status (production truth)
Legend: ✅ live in prod · ⚠️ partial/built-but-not-wired-in-prod · ❌ not in prod (see Architecture doc).

| Domain | Status | Notes |
|---|---|---|
| Worker signup + ID/address verification (admin review) | ✅ | governmentId + proofOfAddress approval gates eligibility |
| Sponsor checkout + donations (Stripe) | ✅ | $100/block, packages, deliverables at $2k+ |
| Routes: create/publish/**direct assign** (`isTest`) | ✅ | admin assigns a specific worker |
| Worker **self-claim** available routes | ✅ | first-come |
| **Cleaning scan flow** (scout→review→clean→complete) | ✅ | `/api/scans/*`; LiDAR/AR/GPS tiers; before/after hotspots; depth/geojson retained |
| Clean **pass/fail adjudication** | ⚠️ | capture + community/peer + admin status; an explicit **admin override before payout** should be confirmed (hybrid) |
| Spots + peer verification, bounties, feed, leaderboard, gamification | ✅ | |
| **Community queue / "load next in line" batch onboarding** | ❌ | exists in Ktor + app UI only; not in prod. See `gap-analysis-queue-onboarding.md` |
| Admin 2FA | ❌ | Face ID re-auth only; branch `feature/spot-map-admin-2fa-faceid` |
| Wallet (worker) | ⚠️ | stubbed endpoints |
| Environment data retention (digital twin) | ✅ | depth/pose/geojson/photos/attestation persisted per scan |

## 5. Requirements that govern all surfaces
1. **Eligibility:** only `verificationStatus=approved` + `proofOfAddressStatus=approved` + in-zone workers may be assigned/claim paid blocks.
2. **Ordering fairness:** when onboarding by queue, order = signup ranking (neighborhood→city→global), gated by verification; carryover for missed offers. (Backfill null rankings before use.)
3. **One block : one resident** for paid blocks; no double-booking; full audit trail (`assigned_by`, timestamps, `isTest`).
4. **Capture integrity:** every capture carries attestation (device, session, live-capture, GPS); prefer depth scan over flat photo where supported.
5. **Verification before payout:** a block reaches a recorded clean/not-clean decision; **admin can override**; only then does payout proceed. Test jobs (`isTest`) never pay.
6. **Data retention:** retain raw scan media + spatial metadata at reconstruction-useful fidelity; define consent + bystander-privacy policy before broad capture.
7. **Money safety:** never auto-move funds; payouts gated by approval + Stripe; sponsor funds reserved only for non-test assignments.
8. **Client↔API contract:** the app must only depend on endpoints that exist in the **deployed** API (see parity gap in Architecture doc).

## 6. Roadmap (priority order)
- **P0 (now):** Run the two North Lawndale 10-block sessions via **direct ordered assignment** (Option A in the gap doc); finish/confirm the **admin override** for scan pass/fail; remove the test route; backfill rankings.
- **P1:** Port **queue/batch release** endpoints into the Next.js API (Option B) to make "load next in line" a real automated button driving the app's existing offer UI; server-side ML "after has less debris" check; admin 2FA.
- **P2:** Offline capture/sync for dead zones; digital-twin / game data export pipeline; multi-community scaling; decide long-term backend (keep Next.js vs. migrate to Ktor).

## 7. Success metrics
100% of assigned blocks reach a final verification decision; payouts fire on approval; 0 double-bookings; 100% of completed blocks have retained environment data; first-attempt capture pass rate ≥ target (set after first real session).

## 8. Open decisions
- Queue path (A/B/C) — see gap doc.
- Long-term backend (Next.js-only vs. Ktor migration).
- Scan adjudication threshold + who overrides.
- Data consent/retention + bystander privacy policy.
- "2-lap perimeter" vs. the implemented scout→hotspot→clean model (confirm after on-device test).
