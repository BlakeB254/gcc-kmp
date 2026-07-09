# Litter Stats — Attribution & Non-Double-Counting

**Date:** 2026-07-09 · **Status:** Requirement captured, design NOT approved
**Scope:** Spec 6 of the scan/map series

## Requirement (Blake, 2026-07-09)

> "The litter count and trash detection on a block for the set $100 jobs should be set to
> that job for the stats, and the one that starts in a scan should work for the user's
> entire scan session. And for the scans it's important that we keep the historical scan
> data and put some protection in place that when a mess is scanned multiple times over
> time it's not adding the weight over and over to our community litter stats."

Three requirements:
1. **Job-scoped stats** — litter found during a paid block job attributes to that job.
2. **Session-scoped stats** — litter found in an ad-hoc environment scan attributes to that
   scan session.
3. **Idempotent community totals** — re-scanning the same mess must not re-add its weight.

## What already exists (verified)

**Requirement 3 is already solved at the observation layer, and Spec 1 inherits it.**

`site/src/lib/world-observations.ts::recordWorldObservation` (`:115-180`):
- "Every insert is a new immutable row" — **history is preserved** (requirement: keep
  historical scan data ✓).
- Supersession only flips `is_current = false` on the prior row:
  - A **clean re-scan supersedes ALL currently-dirty items in the cell** (`:128-146`).
  - A **new dirty observation of the same `itemType` in the same cell supersedes the prior
    one** (`:148-179`).
- Superseded attributes are merged forward (`:60`) so a lean re-scan doesn't wipe an
  earlier weight/width measurement.

Consequences:
- Any aggregate filtered on `is_current = true` counts each mess **once**, at its latest
  known state. Spec 1's `litterCount` / `litterWeightGrams` rollup does exactly this
  (`site/src/app/api/map/coverage/route.ts::currentDirtyLitterInBbox`), so **the map is
  already double-count-safe.**

## The actual gaps

### G1. Community totals do NOT flow through `world_observations`

`world_observations` is only aggregated by the map endpoints. Community/worker totals come
from a **different, accumulating** path — `route_scans.total_trash_volume_liters` /
`.total_trash_weight_lbs`, written at scan completion, plus per-worker stats. Those add up
per completed scan and have **no supersession semantics**. Scanning the same alley twice
plausibly adds its weight twice.

**This must be verified before any stats work**: read
`site/src/app/api/scans/[id]/complete/route.ts` and trace every writer of
`total_trash_weight_lbs` and of worker/community totals.

### G2. Observations carry no job or session attribution

`world_observations` has `scan_hotspot_id` and `community_report_id`, but **no
`route_scan_id` and no `env_scan_session_id`**. There is no column that answers "which job
earned this" or "which scan session found this". Requirements 1 and 2 cannot be satisfied
by the current schema.

### G3. The supersede key is `(locationCellId, itemType)`

Two *distinct* bottles in the same 10m cell supersede one another — the second replaces the
first rather than adding. So `litterCount` under-counts dense litter of a single type. This
is a deliberate anti-duplication trade-off, but it means "litter count" is really "distinct
item types currently on record per cell", not "number of pieces of trash". Any HUD counter
built on it (Spec 3's live trash counter) must not claim otherwise.

### G4. Weights are almost never recorded

Of 52 dirty litter observations around South Millard, **zero** carry
`estimatedWeightGrams` (checked via `/api/map/observations`). Weight is only estimated on a
diagnosed close-range capture, which in practice never happens. **A community weight total
computed today would be ~0 regardless of double-counting.** Fix diagnosis (Spec 3) before
investing in weight aggregation.

## Design sketch (to brainstorm)

- Add `route_scan_id` and `env_scan_session_id` (both nullable) to `world_observations`,
  set at insert from the active scan context. Additive; no backfill needed.
- Community totals become a **query over `is_current` observations**, not an accumulating
  counter — idempotent by construction, and it survives supersession for free. Retire the
  accumulating `total_trash_weight_lbs` writers, or recompute them from observations.
- Job stats = observations whose `route_scan_id` belongs to that job.
- Session stats = observations whose `env_scan_session_id` matches, which also gives the
  scan HUD (Spec 3) its counter source.
- Historical queries stay possible because superseded rows are never deleted — "what did
  this cell look like on July 7" is `is_current = false` plus `captured_at` filtering.

## Open questions

1. Should a *cleaned* mess subtract from community totals, or should totals be
   "cumulative litter ever found" vs. "litter currently on record"? These are different
   products; the current accumulating counters implicitly claim the former while the
   `is_current` model naturally expresses the latter.
2. G3: is per-item counting worth a schema change (e.g. an `item_index` in the supersede
   key), or is "distinct types per cell" the honest unit?

## Related

- Spec 1 (`2026-07-09-problem-area-map-legibility-design.md`) already filters `is_current`.
- Spec 5 (data pipeline) needs `scan_sessions` in Neon, which is the natural home for
  `env_scan_session_id`.
