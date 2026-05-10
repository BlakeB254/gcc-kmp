-- Link Sign in with Apple accounts by Apple's stable `sub` claim.
--
-- Email is only present in Apple's identity token on first authorization,
-- so repeat logins must use the verified provider subject rather than a
-- client-supplied email value.
ALTER TABLE "workers" ADD COLUMN IF NOT EXISTS "apple_user_id" varchar;

DO $$ BEGIN
    ALTER TABLE "workers"
        ADD CONSTRAINT "workers_apple_user_id_unique" UNIQUE ("apple_user_id");
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;
