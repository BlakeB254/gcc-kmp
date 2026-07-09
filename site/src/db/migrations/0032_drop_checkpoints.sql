-- Hand-written cleanup for the retired checkpoint workflow.
-- Production schema changes should still be applied with `npm run db:push`
-- per CLAUDE.md until the Drizzle migration journal is repaired.
DROP TABLE IF EXISTS "route_checkpoints";
ALTER TABLE "blocks" DROP COLUMN IF EXISTS "checkpoints";
DROP TYPE IF EXISTS "enum_route_checkpoints_type";
