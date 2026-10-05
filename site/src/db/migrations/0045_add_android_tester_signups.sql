-- Android closed-test tester signups (/android-beta).
-- Hand-written and idempotent, per the schema policy in CLAUDE.md: apply with
-- psql (or let `npm run db:push` reconcile schema.ts). Additive only.
CREATE TABLE IF NOT EXISTS "android_tester_signups" (
  "id" serial PRIMARY KEY NOT NULL,
  "email" varchar(254) NOT NULL,
  "name" varchar(120),
  "source" varchar(64),
  "status" varchar(16) DEFAULT 'new' NOT NULL,
  "added_at" timestamp with time zone,
  "invite_sent_at" timestamp with time zone,
  "joined_at" timestamp with time zone,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "android_tester_signups_email_unique" UNIQUE("email")
);
