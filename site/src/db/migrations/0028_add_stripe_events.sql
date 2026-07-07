-- Hand-written migration (see CLAUDE.md: drizzle-kit generate is not trusted
-- until the snapshot chain is repaired; db:push against schema.ts is the
-- canonical deploy mechanism and this file is the recorded artifact).
--
-- Stripe webhook event dedupe ledger. The webhook handler inserts the Stripe
-- event id with ON CONFLICT DO NOTHING immediately after signature
-- verification; if no row is returned the event was already processed and the
-- handler acks with 200 without re-applying side effects.
CREATE TABLE IF NOT EXISTS "stripe_events" (
	"id" varchar(255) PRIMARY KEY NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
