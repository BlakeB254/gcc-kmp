# Test Plan — iOS Cleaning Sequence (route-queue + scans)

Full run-through before real users clean the two upcoming 10-block North Lawndale sessions. Grounded in the actual implementation (4-phase scan: SCOUTING → SCOUT_REVIEW → CLEANING → COMPLETE; capture tiers PRO_SCANNER/SCANNER/BASIC; `RouteReleaseBatch`, `RouteCommunityQueue`, `ScanHotspot`).

**Build under test:** TestFlight build 100+ from `wip/route-queue-and-scans` (see TestFlight runbook).
**Devices:** 1× LiDAR (iPhone Pro) for depth, 1× non-Pro for AR/GPS fallback.

---

## Phase 0 — Pre-flight (data + env)
- [ ] Confirm the TestFlight build's API points at the intended environment (prod vs staging).
- [ ] Confirm North Lawndale **service area + eligible zips** (e.g., 60623/60624) exist server-side.
- [ ] Seed/verify: ≥12 verified test workers in those zips (`verificationStatus` + `proofOfAddress` = approved), with **non-null `signupRankings`**.
- [ ] Confirm ≥10 **routes** exist and can be published for the session.
- [ ] Admin login works (JWT admin/super-admin; Face ID re-auth on device).

## Phase 1 — Test mode (no real money)

### 1A Queue & selection
- [ ] Admin lists workers; only fully-verified residents of eligible zips are selectable.
- [ ] Create a `route-release` batch with 10 routes → run a wave (`/process`) → exactly 10 distinct worker↔route offers, in expected order (carryover → never-offered → signup ranking).
- [ ] Add a brand-new verified worker → reruns place them by signup ranking, not ahead of carryover.
- [ ] Shortfall: 12 routes, 10 workers → 10 offered, 2 routes left open + flagged.

### 1B Offers & acceptance (1 worker : 1 block)
- [ ] Worker receives offer notification; accept → `Job` claimed, route marked claimed, no double-claim.
- [ ] Let an offer expire → carryover applies; next wave re-offers the open route to the next in line.
- [ ] Daytime-window + offer-expiry respected.

### 1C Admin manual / route assignment
- [ ] `POST /api/admin/routes/{id}/assign` to a specific worker with `isTest=true` → assigned, **no payout reserved**, worker notified.
- [ ] Reassign after a simulated no-show → states transition cleanly.

### 1D Scan/clean — LiDAR (PRO_SCANNER)
- [ ] Start assigned route → **SCOUTING**: checkpoints reveal (fog-of-war), GPS breadcrumb records, hotspots captured with before-photo + depth + trash tags; **live feedback** (coverage bar, direction banner) updates.
- [ ] **SCOUT_REVIEW**: confirm hotspots to clean.
- [ ] **CLEANING**: routed back to each hotspot; proximity alert within ~15 m; capture after-photo + re-scan; depth delta computed.
- [ ] Cannot submit until every in-scope hotspot has before + after.
- [ ] **COMPLETE**: submit → points awarded; status COMPLETED.

### 1E Scan/clean — non-Pro (SCANNER/BASIC fallback)
- [ ] Same flow falls back to AR photo (anchor stored) or GPS flat photo + manual radius; feedback still fires; fallback tier logged.

### 1F Hybrid verification
- [ ] Auto result produced per block (after has measurably less debris / after-photo present).
- [ ] Admin views before/after (`GET /api/admin/scans/{id}`) and can **confirm** or **override** the decision; final decision recorded with reviewer + time.
- [ ] Final decision gates payout state (test job stays unpaid).

### 1G Data retention (digital-twin readiness)
- [ ] For a completed block, retrieve all media + metadata: before/after URLs, `depthData`, `arAnchorData`, scout/clean GeoJSON, hotspot lat/lng + types + volume, timestamps, `CaptureAttestation`, device type.
- [ ] Confirm originals stored (not just thumbnails).

### 1H Edge cases
- [ ] Kill app mid-SCOUTING, reopen → scan state restored, no data loss.
- [ ] Signal loss mid-capture → defined behavior (queue/sync or clear error), not silent loss.
- [ ] Camera/location permission denied → clear recoverable prompts.
- [ ] Leave the geofence → app warns/handles.

## Phase 2 — Controlled real dress rehearsal
Run with 2–3 trusted real workers on 2–3 real North Lawndale blocks, production build/env.
- [ ] Real batch or direct assignments; real scout→clean on real streets (real GPS, trash, lighting).
- [ ] Admin verifies via hybrid flow; confirm a **real payout fires end-to-end (Stripe)** on one approval.
- [ ] Debrief: was scan feedback/route guidance clear? Time per block? Capture pass rate?
- [ ] Confirm environment data landed for real captures.

**Go / No-Go for full sessions:** all rehearsal blocks reach a final decision, payout fires, data retained, no blocking UX confusion.

## Phase 3 — The two 10-block sessions
- [ ] Create batch 1 (10 routes) → release/process waves → monitor offers, claims, scans, verifications.
- [ ] Repeat for batch 2.
- [ ] Post-session: reconcile approved vs. paid; export environment data; record metrics (PRD §10).

## Tracking
| ID | Area | Device | Result | Notes/bug |
|----|------|--------|--------|-----------|
| 1A | Queue/selection | — | | |
| 1B | Offers/accept | — | | |
| 1C | Admin assign | — | | |
| 1D | Scan (LiDAR) | Pro | | |
| 1E | Scan (fallback) | non-Pro | | |
| 1F | Hybrid verify | — | | |
| 1G | Data retention | — | | |
| 1H | Edge cases | both | | |
| 2  | Dress rehearsal | both | | |
