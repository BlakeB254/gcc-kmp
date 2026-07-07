# One-off cleanup: indoor-credited geotiles (2026-07-06)

Blake's indoor scan credited ~3 geotiles before this PR's fixes. After deploy:

1. In the app, tap each wrong tile → CoverageCellInfoCard shows its `cellId`.
2. From site/, with .env.local's DATABASE_URL:

    psql "$DATABASE_URL" <<'SQL'
    BEGIN;
    -- $CELLS: replace with the tapped cellIds, e.g. ('cell_a','cell_b','cell_c')
    DELETE FROM points_ledger
      WHERE reference_type = 'scan_coverage_event'
        AND reference_id IN (SELECT id FROM scan_coverage_events WHERE location_cell_id IN $CELLS);
    DELETE FROM scan_coverage_events WHERE location_cell_id IN $CELLS;
    DELETE FROM cell_coverage_summary WHERE location_cell_id IN $CELLS;
    COMMIT;
    SQL

3. Verify: the tiles return to fog on the map (pull-to-refresh / reopen).

MERGE ACTIONS for the PR itself:
- ORDERING IS NOT OPTIONAL: run `cd site && npm run db:push` against prod
  BEFORE merging/deploying the server code in this PR. The new server code
  reads `scan_coverage_events.quality` and `cell_coverage_summary.best_quality`
  / `last_close_scanned_at` — if the code deploys first and the migration
  runs second, every coverage request 500s against prod until db:push
  catches up. The reverse order (db:push first, old code deployed) is fully
  safe: the columns are additive with defaults and the not-yet-deployed old
  code just ignores them.
- `cd site && npm run db:push` (new columns: scan_coverage_events.quality,
  cell_coverage_summary.best_quality / last_close_scanned_at)
- `npm run db:push` also carries a Postgres ENUM addition — `scan_coverage_close_upgrade`
  was added to `points_action` (pointsActionEnum) — enum value additions are supported
  by db:push but verify it applies cleanly.
- TestFlight build + device walk test: indoor open (gate flips, minimap
  un-credits, tiles never appear on main map), outdoor sweep (light shade),
  close pass (dark shade + AR quads appear), route lap completion still
  reachable, phase-2 "move closer" chip.
