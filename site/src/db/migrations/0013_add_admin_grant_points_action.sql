-- Adds two new values to the points-ledger action enum so admins can
-- record manual point grants distinctly:
--   * admin_grant : generic admin-issued grant or correction
--   * bug_report  : reward for discovering a bug in the app or website
--
-- IF NOT EXISTS keeps this migration idempotent across replays.
ALTER TYPE "enum_points_ledger_action" ADD VALUE IF NOT EXISTS 'admin_grant';
ALTER TYPE "enum_points_ledger_action" ADD VALUE IF NOT EXISTS 'bug_report';
