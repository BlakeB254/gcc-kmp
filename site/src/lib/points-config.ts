/**
 * Point weights for the distance-tracked scan coverage economy (design spec
 * §5). Deliberately plain exported constants, not a DB-driven config table —
 * tunable post-launch by editing this file, per the spec's own explicit
 * non-goal ("exact numeric point values... not an architecture decision").
 */
export const POINTS_PER_METER = {
  NEW_AREA: 10,          // first-ever scan of a cell, close quality
  NEW_AREA_GRAZED: 4,    // first-ever scan, sweep quality — partial award (spec §3)
  CLOSE_UPGRADE: 6,      // grazed→close upgrade pays the remainder (4 + 6 = 10)
  STALE_RESCAN: 5,
  FRESH_RESCAN: 0, // no incentive to farm the same block repeatedly in one sitting
} as const

export const RECTIFICATION_BONUS_POINTS = 25

export const STALE_AFTER_MS = 24 * 60 * 60 * 1000

/**
 * Upper bound on distanceMeters for a single coverage event, enforced at
 * the API boundary (site/src/app/api/scans/coverage/route.ts) before the
 * event ever reaches recordCoverageEvent / pointsLedger. Generous enough
 * for a real multi-block route-scan session batched into one event, small
 * enough to bound the payoff of a spoofed distanceMeters value. Broader
 * rate-limiting / elapsed-time plausibility checks are deferred to a
 * future anti-abuse spec.
 */
export const MAX_DISTANCE_METERS_PER_EVENT = 1000
