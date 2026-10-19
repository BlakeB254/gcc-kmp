-- Manifests the scan indexer permanently rejected (see site/src/lib/scan-index/
-- indexer.ts and the scanManifestRejects table in schema.ts). The indexer
-- skips an entry until its object is re-uploaded; the watchdog alerts on each
-- entry once (alerted_at) instead of failing on it forever.
--
-- IF NOT EXISTS: `npm run db:push` is the canonical deploy mechanism here, so
-- this recorded artifact must stay idempotent against an already-pushed schema.
CREATE TABLE IF NOT EXISTS "scan_manifest_rejects" (
  -- text: an over-long key is exactly what gets rejected; its insert must not fail.
  "r2_key" text PRIMARY KEY NOT NULL,
  "reason" text NOT NULL,
  "size" bigint NOT NULL,
  "last_modified" timestamp with time zone NOT NULL,
  "first_seen_at" timestamp with time zone DEFAULT now() NOT NULL,
  "alerted_at" timestamp with time zone
);
-- An earlier draft of this file used varchar(500); widen it where that ran.
ALTER TABLE "scan_manifest_rejects" ALTER COLUMN "r2_key" TYPE text;
