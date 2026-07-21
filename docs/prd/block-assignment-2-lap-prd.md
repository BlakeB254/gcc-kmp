# PRD — Block Assignment Completion (2-Lap System)

**Status:** CANONICAL — supersedes prior specs/plans for the block-assignment
scan/clean flow. When older docs contradict this PRD, this PRD wins.

**Owner:** Blake (product). Last canonized 2026-07-12.

**Foundation principle (binding):** the block-assignment AR experience IS the
standalone environment scanner (see `env-scan-prd.md`) parameterized with a
block corridor and lap context. It must never fork into a separate AR stack:
same surface, same grid, same detection/tap flow, same minimap, same capture
pipeline. Lap-specific behavior is layered on top (corridor coverage target,
lap guidance, hotspot before/after workflow).

---

## 1. The flow

A worker claims a block job and completes it with exactly **two laps**:

```
claim job (block available → in_progress, 24 h window)
  → start job
  → LAP 1 — SCOUTING ("document")
  → SCOUT_REVIEW (review findings)
  → LAP 2 — CLEANING ("clean")
  → complete scan → complete job → completion report → payout
```

### Lap 1 — Scout / document
- Walk the block corridor with the AR scanner; coverage credits measured
  cells inside the corridor; the minimap shows the corridor as "your zone"
  cut out of the veil, filling in live.
- Litter found is tagged as **hotspots** (before-photo, type, severity,
  weight estimate) via the same reticle/tap flow as the env scan — implemented via the shared TapToConfirmEngine (2026-07, route-lap tap parity plan).
- Lap 1 completes at **≥95 % corridor fill** (auto-advance prompts review),
  or ≥85 % + closed loop, or all sections recorded. A worker must NEVER be
  stranded: force-complete is always reachable behind a confirm dialog.
- Lap 1 uploads its own session manifest (`lapKind = "baseline"`).

### Scout review
- Shows what lap 1 found: hotspot list, problem areas (hotspots clustered
  within 30 m, largest first), coverage result.
- Primary action: "Start Cleaning" (or "Review Documentation" when zero
  hotspots).

### Lap 2 — Clean
- Guided lap: the worker is steered to problem areas (minimap target ring +
  AR beacon), largest first.
- Each hotspot is resolved: **cleaned** (after-photo + verification) or
  **skipped/documented-only**. Lap-2 progress reads in estimated lbs cleaned
  vs. lap-1 total (display only — never a gate).
- Lap 2 records its own session (`lapKind = "clean"`, fresh recorder) and
  uploads its manifest + capture chunks.
- Completing with **zero cleaned hotspots** (nothing found, or all skipped)
  is allowed but must be an explicit confirmed choice, never a default tap.

### Completion
- Complete Scan submits the clean lap (paths, totals, covered cells) →
  job flips to completed → block returns to `available` (blocks are
  recurring units by design) → completion report generates (duration, route
  mini-map, weight cleaned, before/after pairs) → payout via the
  review/approval flow.

## 2. Lap visibility (UX canon)

- A persistent lap chip shows the current stage on every lap screen:
  "Lap 1 of 2 · Document" → "Lap 1 done · Review" → "Lap 2 of 2 · Clean".
- **Laps are timed and the clock is visible**: the lap chip carries a live
  lap clock (mm:ss, restarting at the lap-2 transition); per-lap durations
  land server-side via the phase timestamps (scout/clean started/completed).
- **Lap 1's findings are visible while scouting**: a tally chip (items found
  + estimated lbs) rides under the lap chip and carries into review.
- The block boundary is a visible **scan limit** everywhere: AR barrier
  walls in the camera (green outer / amber exclusions + outside-boundary
  veil) and a heavier out-of-bounds wash on the minimap beyond the outer
  ring.
- Lap transitions are prompted, never hidden: hitting the lap-1 threshold
  auto-advances into review; review's primary button enters lap 2.

## 3. Server enforcement (canon — the 2-lap invariant is not client-only)

- **Phase order is validated:** `scouting → scout_review → cleaning →
  submitting → complete`, same-phase writes allowed (idempotent retries),
  one step forward otherwise, 409 on anything else. `scouting → complete`
  can never skip the cleanup lap.
- **Job completion requires the scan:** a worker's job completes only with a
  COMPLETE scan session by that worker for the block's route (409 otherwise).
  Admin completion stays ungated as the manual recovery path.
- **Completion reports self-heal:** if generation failed at completion time,
  the report GET generates on demand (idempotent upsert per job) — the client
  must never be stranded on "still generating".

## 4. Data canon

- One `route_scans` row per assignment attempt, phase-stamped
  (scout/clean started/completed timestamps, both lap paths).
- Two R2 session manifests per completed assignment (`baseline` + `clean`),
  carrying capture chunks + geo-ref events per the splat-registration
  contract; lap sessions rotate recorder identity at the lap transition.
- Hotspots live on the scan session; `world_observations` receives the same
  litter for the shared world model/map.

## 5. Deprecated — never reintroduce

| Feature | Status |
|---|---|
| In-camera AR fog on route laps | REMOVED (with the env-scan fog — "no fog") |
| AR neon coverage/vector quads on route laps | REMOVED |
| All-or-nothing section-recording completion gate (stranding) | REPLACED by force-complete escape |
| Unvalidated phase PATCH (client-only lap invariant) | REPLACED by server phase validation |
| Job completion without any scan session | REMOVED (server 409) |
| Separate/forked AR stack for route laps | FORBIDDEN (foundation principle) |

## 6. Open items

- Corridor-aware guidance in lap 2 when problem areas are empty (today it
  falls back to a generic prompt).
- Lap-2 hotspot verification scoring surfaced to the worker (today the score
  records silently).
- Payout UX after completion (report → payout status visibility).
- Route-lap LiDAR ground-candidate auto-detect parity (GroundCandidateTracker
  drive loop is still env-only).
- Rejected route taps ride telemetry only (no manifest rejected list).
