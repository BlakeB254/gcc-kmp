-- Route administration v2 (plan docs/plans/route-admin-plan-2026-08-20):
--   routes.claim_status        — claim visibility independent from publish:
--                                'closed' (default) | 'open' | 'scheduled'.
--                                Publishing stays the visibility switch;
--                                claim_status is the accepting-claims switch.
--   routes.publish_at /
--   routes.unpublish_at        — scheduled (un)publish timestamps (the shared
--                                DTOs already carried these; the server ignored
--                                them until now).
--   route_release_routes.position — explicit per-batch ordering for the
--                                release queue (admins drag to reorder; offer
--                                candidate presentation honors position ASC).
--
-- Guarded with IF NOT EXISTS because `npm run db:push` is the canonical deploy
-- mechanism in this repo, not `drizzle-kit migrate` — this file is a recorded
-- artifact that must stay idempotent against an already-pushed schema.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'enum_routes_claim_status') THEN
    CREATE TYPE "enum_routes_claim_status" AS ENUM ('closed', 'open', 'scheduled');
  END IF;
END $$;

ALTER TABLE "routes"
  ADD COLUMN IF NOT EXISTS "claim_status" "enum_routes_claim_status" DEFAULT 'closed' NOT NULL;

-- Backfill: existing published routes keep today's behavior exactly
-- (published == claimable). Everything else stays 'closed'.
UPDATE "routes"
  SET "claim_status" = 'open'
  WHERE "status" = 'published' AND "claim_status" = 'closed';

ALTER TABLE "routes" ADD COLUMN IF NOT EXISTS "publish_at" timestamp with time zone;
ALTER TABLE "routes" ADD COLUMN IF NOT EXISTS "unpublish_at" timestamp with time zone;

ALTER TABLE "route_release_routes"
  ADD COLUMN IF NOT EXISTS "position" integer DEFAULT 0 NOT NULL;

-- Backfill a stable 1..N order per batch from insertion order.
WITH ranked AS (
  SELECT "id",
         row_number() OVER (PARTITION BY "batch_id" ORDER BY "created_at", "id") AS pos
  FROM "route_release_routes"
)
UPDATE "route_release_routes" AS r
  SET "position" = ranked.pos
  FROM ranked
  WHERE r."id" = ranked."id";

CREATE INDEX IF NOT EXISTS "idx_route_release_routes_batch_position"
  ON "route_release_routes" ("batch_id", "position");
