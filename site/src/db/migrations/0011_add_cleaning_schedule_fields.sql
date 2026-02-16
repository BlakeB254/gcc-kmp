-- Add preferred cleaning schedule fields to transactions and blocks tables
-- These allow sponsors to specify when they want their sponsored block cleaned

-- Add to transactions table (sponsor's original request)
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "preferred_cleaning_date" timestamp with time zone;
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "preferred_cleaning_time_start" varchar(5);
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "preferred_cleaning_time_end" varchar(5);

-- Add to blocks table (what workers see when claiming)
ALTER TABLE "blocks" ADD COLUMN IF NOT EXISTS "preferred_cleaning_date" timestamp with time zone;
ALTER TABLE "blocks" ADD COLUMN IF NOT EXISTS "preferred_cleaning_time_start" varchar(5);
ALTER TABLE "blocks" ADD COLUMN IF NOT EXISTS "preferred_cleaning_time_end" varchar(5);

-- Add index for date-based queries
CREATE INDEX IF NOT EXISTS "blocks_preferred_cleaning_date_idx" ON "blocks" ("preferred_cleaning_date");
