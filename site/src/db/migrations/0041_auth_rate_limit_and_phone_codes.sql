-- Auth security tables (audit 2026-08-20, findings F-03 / F-06):
--   rate_limit_events        — Postgres-backed fixed-window throttle for the
--                              public auth endpoints (see site/src/lib/rate-limit.ts).
--                              Replaces the in-memory Maps that reset on every
--                              serverless instance.
--   phone_verification_codes — durable store for SMS verification codes with a
--                              failed-attempt counter and resend cooldown
--                              (see site/src/lib/phone-verification.ts).
--
-- Guarded with IF NOT EXISTS because `npm run db:push` is the canonical deploy
-- mechanism in this repo, not `drizzle-kit migrate` — this file is a recorded
-- artifact that must stay idempotent against an already-pushed schema.
CREATE TABLE IF NOT EXISTS "rate_limit_events" (
  "id" bigserial PRIMARY KEY NOT NULL,
  "key" varchar(255) NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS "rate_limit_events_key_created_at_idx"
  ON "rate_limit_events" ("key", "created_at");

CREATE TABLE IF NOT EXISTS "phone_verification_codes" (
  "phone" varchar(20) PRIMARY KEY NOT NULL,
  "code" varchar(6) NOT NULL,
  "expires_at" timestamp with time zone NOT NULL,
  "failed_attempts" integer DEFAULT 0 NOT NULL,
  "send_count" integer DEFAULT 0 NOT NULL,
  "window_started_at" timestamp with time zone DEFAULT now() NOT NULL,
  "last_sent_at" timestamp with time zone
);
