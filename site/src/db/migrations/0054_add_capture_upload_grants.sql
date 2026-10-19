-- Upload grants: every raw/ capture key a presigned PUT was issued for, and the
-- worker it was issued to (site/src/lib/scan-index/grants.ts). The scan indexer
-- only accepts manifests uploaded after the first grant (+2h grace) whose key,
-- and every key they name, was granted to one worker; it stamps the session's
-- worker_id. 0053 is taken by another branch.
--
-- IF NOT EXISTS: `npm run db:push` is the canonical deploy mechanism here, so
-- this recorded artifact must stay idempotent against an already-pushed schema.
CREATE TABLE IF NOT EXISTS "capture_upload_grants" (
  "r2_key" text PRIMARY KEY NOT NULL,
  "worker_id" integer NOT NULL,
  "device_id" varchar(128),
  "session_id" varchar(128),
  "issued_at" timestamp with time zone DEFAULT now() NOT NULL,
  "last_issued_at" timestamp with time zone DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS "capture_upload_grants_worker_idx" ON "capture_upload_grants" ("worker_id");
CREATE INDEX IF NOT EXISTS "capture_upload_grants_issued_idx" ON "capture_upload_grants" ("issued_at");

ALTER TABLE "scan_sessions" ADD COLUMN IF NOT EXISTS "worker_id" integer;

-- First worker granted a key under a session folder owns that session; every
-- other worker is refused any key there. Only kind = 'session' is written:
-- device folders are not bound (shared test handsets).
CREATE TABLE IF NOT EXISTS "capture_namespace_owners" (
  -- The folder segment itself (raw/{cell}/{ns_id}/...), whatever request shape
  -- names it; only session-shaped requests create rows.
  "ns_id" varchar(64) PRIMARY KEY NOT NULL,
  "worker_id" integer NOT NULL,
  "first_granted_at" timestamp with time zone DEFAULT now() NOT NULL
);
-- An earlier draft of this file used varchar(500); widen it where that ran.
ALTER TABLE "capture_upload_grants" ALTER COLUMN "r2_key" TYPE text;
