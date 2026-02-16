ALTER TABLE "sponsors" ADD COLUMN "email_verified" boolean DEFAULT false;--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "email_verification_token" varchar;--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "email_verification_expires" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "email_verified_at" timestamp with time zone;