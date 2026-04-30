CREATE TYPE "public"."enum_sponsor_deliverable_items_kind" AS ENUM('recognition_wall', 'certificate', 'tax_receipt', 'newsletter_feature', 'social_recognition');--> statement-breakpoint
CREATE TYPE "public"."enum_sponsor_deliverable_items_status" AS ENUM('pending', 'completed');--> statement-breakpoint
CREATE TYPE "public"."enum_sponsor_deliverables_source" AS ENUM('online', 'offline');--> statement-breakpoint
CREATE TYPE "public"."enum_sponsor_deliverables_status" AS ENUM('pending', 'in_progress', 'completed');--> statement-breakpoint
CREATE TABLE "sponsor_deliverable_items" (
	"id" serial PRIMARY KEY NOT NULL,
	"package_id" integer NOT NULL,
	"kind" "enum_sponsor_deliverable_items_kind" NOT NULL,
	"status" "enum_sponsor_deliverable_items_status" DEFAULT 'pending' NOT NULL,
	"data" jsonb,
	"triggered_by_worker_id" integer,
	"triggered_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "sponsor_deliverable_packages" (
	"id" serial PRIMARY KEY NOT NULL,
	"sponsor_id" integer NOT NULL,
	"transaction_id" integer,
	"sponsor_package_id" integer,
	"amount_cents" integer NOT NULL,
	"source" "enum_sponsor_deliverables_source" NOT NULL,
	"external_reference" varchar,
	"tier" varchar(20) NOT NULL,
	"status" "enum_sponsor_deliverables_status" DEFAULT 'pending' NOT NULL,
	"notes" text,
	"created_by_worker_id" integer,
	"completed_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
