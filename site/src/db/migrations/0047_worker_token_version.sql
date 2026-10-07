-- Session revocation for mobile JWTs (security PR sec/auth-and-payout-switch).
-- Hand-written and idempotent, per the schema policy in CLAUDE.md. Additive:
-- one NOT NULL column with a constant default, so existing rows read 0 and
-- every token minted before this column existed (no `tv` claim, treated as 0)
-- stays valid until the worker's version is bumped.
--
-- MUST be applied to production BEFORE the code that selects workers.* ships:
-- drizzle's `db.select().from(workers)` names token_version explicitly.
ALTER TABLE "workers" ADD COLUMN IF NOT EXISTS "token_version" integer DEFAULT 0 NOT NULL;
