# PRD — Route Queue, Batch Onboarding & Cleaning Scans

**Product:** Global Community Cleaners (GCC) — Worker iOS app
**Bundle / App:** `com.globalcommunitycleaners.worker` · App Store ID 6760203806 · Team 7BBNW25LZM
**Branch this documents:** `wip/route-queue-and-scans-20260523`
**Date:** 2026-06-24 · **Author:** Blake (with Claude)
**Relationship to canon:** Serves `docs/feature-spec-2026-05.md` §A (canon). This PRD details §B methods for queueing, batch release, and the scan flow so development stays consistent.

> **Status note:** The features below are **implemented on the `wip/route-queue-and-scans` branch (build 99 / v1.0.6)** but are **NOT yet in TestFlight** (last TestFlight = build 88, pre-dating this branch). This PRD doubles as the spec to validate before/while promoting that build.

---

## 1. Problem & Goal

GCC pays one resident $100 to clean one block, before/after verified. As cleaning sessions are scheduled (immediate need: **two sessions of 10 blocks each in North Lawndale**), we must (a) select the right verified residents in fair order and assign them to blocks, (b) let admins drive that on demand or assign specific routes, and (c) run a trustworthy in-app cleaning + scan sequence that proves the work and retains high-quality spatial data (canon §A.2 — "Pokémon-Go-grade" capture) for future digital-twin / game use.

## 2. Roles

- **Worker** — verified resident; receives an offer or direct assignment, runs the scan/clean flow, gets paid on approval.
- **Admin / Super-admin** — schedules release batches, triggers waves ("load next in line"), directly assigns routes, reviews/verifies. JWT role-gated (`requireAdmin()`); Face ID re-auth on device. (No 2FA yet — see Gaps.)
- **Sponsor** — funds blocks; payout reserved from sponsor wallet at assignment.

---

## 3. Feature A — Community Queue & Selection (IMPLEMENTED, server-side)

**Where:** `server/.../services/RouteQueueService.kt` (`selectCandidates`, `syncQueueRows`); table `RouteCommunityQueue`. Models in `shared/.../model/RouteQueue.kt`, `Worker.kt`, `WorkerEnums.kt`.

**Eligibility (hard gates):**
- `Workers.verificationStatus == "approved"`
- `Workers.verificationProofOfAddressStatus == "approved"`
- `Workers.addressZip ∈ batch.eligibleZipCodes`
- not currently on a `claimed | in_progress | pending_review` job
- not already offered in the same batch

**Order (`sortedWith`):**
1. `isCarryover` desc — workers who missed a prior offer get first shot (once, `carryoverAttempts < 1`).
2. `lastOpportunityAt` asc — never-offered (null) come before previously-offered (rotation/fairness).
3. `queueRank` asc — `signupRankingsNeighborhood ?? city ?? global` → **earlier signup = earlier in line**.
4. `workerId` asc — deterministic tiebreaker.

**Acceptance criteria**
- Only fully-verified residents of the eligible zips are ever selectable.
- Among new candidates, order follows neighborhood signup ranking.
- Selection is deterministic and re-runnable across waves without re-offering active/offered workers.

**Known risks to resolve before the 10-block runs**
- ⚠️ **Null signup ranking** → `queueRank = MAX` → worker sorts last. Backfill `signupRankings*` for all North Lawndale verified workers, or selection order will be wrong for them.
- ⚠️ Confirm `lastOpportunityAt`-before-`queueRank` ordering is the intended fairness model (rotation), vs. strict signup order every time.

---

## 4. Feature B — Scheduled Release Batches / Batch Onboarding (IMPLEMENTED)

**Where:** `RouteQueueService.kt` (`createBatch`, `processBatch`); tables `RouteReleaseBatches`, `RouteJobOffers`, `RouteReleaseRoutes`. Admin route `POST /api/admin/route-releases`.

**Model:** a batch holds `routeIds: List<Int>` (one per block), `eligibleZipCodes`, `releaseAt`, `offerWindowMinutes`, `communityName`, `timezone`, `daytimeWindowStart/End`, `serviceAreaId`.

**Wave behavior (1 user : 1 block):**
1. Wave 1 offers the top-N queue candidates (N = open routes) one route each.
2. Each `RouteJobOffer` is valid for `offerWindowMinutes` (default ~4h), only during the daytime window (e.g., 08:00–20:00 America/Chicago).
3. Expired/declined offers → carryover; next wave fills remaining open routes.
4. Accepted offer → `Job` created (`claimed`), route marked claimed (`RouteReleaseRoutes.status = open → claimed`); worker notified.

**For the two upcoming sessions:** create two batches, each with 10 `routeIds`, `eligibleZipCodes = ["60623","60624"]` (confirm exact zips), Chicago timezone, daytime window.

**Acceptance criteria**
- A 10-route batch yields exactly 10 distinct worker↔route offers per wave (or fewer with a logged shortfall).
- No worker double-offered within a batch; no route double-claimed.
- Offers respect the daytime window and offer expiry.

---

## 5. Feature C — Admin Load-Next-in-Line & Manual Assignment (IMPLEMENTED)

**Where:** `server/.../routes/Admin*Routes.kt`; iOS `composeApp/.../ui/screens/admin/AdminPanelScreen.kt` + `AdminPanelViewModel.kt`.

- **Load next in line:** `POST /api/admin/route-releases/{id}/process` triggers the next wave (manual equivalent of the cron).
- **Direct/manual assignment:** `POST /api/admin/routes/{id}/assign` assigns a specific route to a specific worker (verification checked, payout reserved from sponsor wallet, worker notified). Supports `isTest=true` for **unpaid test assignments**.
- **Route management:** create/update routes + checkpoints, `PUT /api/admin/routes/{id}/publish` to make worker-visible; `allowUnpublished=true` lets admins test draft routes.
- **Worker management:** list, `PATCH .../verify`, `POST .../bulk-action` (verify/suspend/activate).

**Routes vs. blocks:** the route model already supports multi-checkpoint paths; default tested flow is 1 route = 1 block = 1 worker.

**Acceptance criteria**
- Admin can pull the next wave on demand and see assignments.
- Admin can override queue order with a direct assignment; test assignments skip payout.
- Reassignment cleanly transitions route + worker states.

---

## 6. Feature D — Cleaning + Scan Sequence (IMPLEMENTED) — reconciled with the "2-lap" model

**Where:** models `shared/.../model/RouteScan.kt`, `ScanEnums.kt` (`RouteScan`, `ScanHotspot`); `server/.../services/ScanService.kt` + `routes/ScanRoutes.kt`; iOS `RouteScanViewModel.kt`, `RouteScanScreen.kt`, `ScoutScreen.kt`, `iosMain/.../ArScanner.kt`. Canon: spec §B.1–B.4.

**Your "2 laps, 1-block radius" maps to a 4-phase state machine:**

| Your model | Implemented phase | What happens |
|---|---|---|
| Lap 1 — verify trash, capture before, geoscan/images | **SCOUTING** | App reveals checkpoints (fog-of-war); GPS breadcrumb trail (~1 Hz); device-tier capture detects **hotspots** with before-photo, trash-type tags, severity, GPS, and LiDAR depth where available. |
| (between laps) | **SCOUT_REVIEW** | Worker confirms which hotspots to clean. |
| Lap 2 — verify clean in afters | **CLEANING** | App routes back to each hotspot; proximity alert within geofence (~15 m); capture after-photo + re-scan depth → volume delta. |
| Submit | **SUBMITTING → COMPLETE** | Final submission; points awarded weighted by capture quality + coverage. |

**Capture tiers (device-aware, spec §B.1):** PRO_SCANNER (LiDAR → depth/volume), SCANNER (ARKit photo + AR anchor, no depth), BASIC (GPS-tagged flat photo + manual radius). **This is your "geoscan, or if not, images."**

**Live scan feedback (your "on the scan it should give feedback"):** real-time coverage bar, AI direction banner, proximity alerts to uncleaned hotspots, animated hotspot pins, phase progression.

**"Clean" determination (HYBRID — per your decision):**
- Today: hotspot marked cleaned when after-photo submitted + status updated; depth delta computed where available.
- Per decision + spec §B.4: **auto-score (after-scan has measurably less debris) proposes pass; admin can confirm/override.** ⚠️ The server-side ML cross-check and an explicit admin override UI for scan pass/fail are the main pieces to finish (see Gaps).

**Acceptance criteria**
- Cannot submit until each in-scope hotspot has a before + after capture.
- Each after capture is tied to its before hotspot (same `ScanHotspot`).
- A final clean/not-clean decision is recorded per block; admin can override.
- Worker gets feedback at each capture, not just at the end.

---

## 7. Feature E — Environment Data Retention for Digital Twin / Game (IMPLEMENTED)

Canon §A.2 already mandates volumetric, georeferenced, timestamped, attested capture; §B.1 roadmap explicitly includes storing depth point clouds for re-rendering and AR replay. **This is your digital-twin / game requirement, and the data is already being preserved:**

Stored per scan / hotspot: before/after photo URLs (object storage), `depthData` (LiDAR point cloud JSON), `arAnchorData` (AR pose for revisit overlay), `scoutPathGeoJson` / `cleanPathGeoJson` (GPS trails), hotspot lat/lng + trash types + severity + estimated volume/weight + confidence, all timestamps, device type + `CaptureAttestation` (device, OS, session, capture index, live-capture flag).

**Acceptance criteria**
- For any completed block, all media + spatial metadata are retrievable and joined to block/session/worker.
- Depth/pose/accuracy are never downsampled below reconstruction usefulness.
- ⚠️ Consent + bystander-privacy policy (faces/plates) defined before broad production capture.

---

## 8. Gaps / Decisions before the two 10-block sessions

1. **Promote the build:** route-queue + scans are not in TestFlight (88). Build & upload build 99+/v1.0.6 from this branch (see runbook).
2. **Signup-ranking backfill** for verified North Lawndale workers (else queue order is wrong for them).
3. **Confirm eligible zips** for the North Lawndale service area + that 10 routes exist/are published per session.
4. **Hybrid scan verification finish:** auto-score + admin override UI for block pass/fail.
5. **Admin 2FA** (branch `feature/spot-map-admin-2fa-faceid` exists) — decide if required before real money flows.
6. **Offline capture/sync** for dead zones — confirm behavior or accept online-only for pilot.
7. **Consent/retention** policy for environment scans.
8. **"2-lap" intent:** confirm the scout→clean hotspot model satisfies your perimeter "2-lap 1-block radius" mental model, or whether you want an explicit full-perimeter relap.

## 9. Phasing

- **P0 (now):** promote build to TestFlight; backfill rankings; create the two batches; run the test plan; finish hybrid verify enough to adjudicate; retain data.
- **P1:** ML after-scan check; admin 2FA; offline sync; route (multi-block) execution.
- **P2:** digital-twin/game export pipeline; cross-community scaling.

## 10. Success metrics

- 100% of assigned blocks reach a final verification decision; payouts fire on approval.
- 0 double-offers / double-claims; complete assignment audit trail.
- 100% of completed blocks have retained, retrievable environment data.
- First-attempt capture pass rate ≥ target (set after the dress rehearsal).
