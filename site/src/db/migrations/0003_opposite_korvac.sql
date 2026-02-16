CREATE TYPE "public"."enum_sponsors_fund_allocation" AS ENUM('general_fund', 'community_specific');--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "fund_allocation" "enum_sponsors_fund_allocation" DEFAULT 'general_fund';--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "preferred_community" varchar;--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "wait_for_community_launch" boolean DEFAULT false;--> statement-breakpoint
ALTER TABLE "sponsors" ADD COLUMN "let_gcc_choose_blocks" boolean DEFAULT true;