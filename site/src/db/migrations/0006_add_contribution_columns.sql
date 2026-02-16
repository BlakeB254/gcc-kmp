-- Add allocation type enum for contribution categorization
CREATE TYPE "public"."enum_transactions_allocation_type" AS ENUM('general', 'block', 'area');--> statement-breakpoint

-- Add new columns to transactions table for unified contribution model
ALTER TABLE "transactions" ADD COLUMN "allocation_type" "enum_transactions_allocation_type" DEFAULT 'general';--> statement-breakpoint
ALTER TABLE "transactions" ADD COLUMN "display_name" varchar;--> statement-breakpoint
ALTER TABLE "transactions" ADD COLUMN "is_anonymous" boolean DEFAULT false;--> statement-breakpoint

-- Update existing block sponsorship transactions to have 'block' allocation type
UPDATE "transactions" SET "allocation_type" = 'block' WHERE "block_id" IS NOT NULL;
