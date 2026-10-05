-- Block reservations on transactions (/admin/reservations).
-- Hand-written and idempotent, per the schema policy in CLAUDE.md. Additive:
-- four nullable columns, no default, no existing row changes.
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "reserved_block_count" integer;
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "reserved_block_codes" varchar(500);
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "reservation_allocated_at" timestamp with time zone;
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "reservation_note" text;
