-- Add message moderation fields to transactions table
-- Allows admin to hide or redact offensive messages on the support wall

-- Create enum for message status
DO $$ BEGIN
    CREATE TYPE "enum_transactions_message_status" AS ENUM('visible', 'hidden', 'redacted');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

-- Add columns to transactions table
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "message_status" "enum_transactions_message_status" DEFAULT 'visible';
ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "message_redacted_text" text;
