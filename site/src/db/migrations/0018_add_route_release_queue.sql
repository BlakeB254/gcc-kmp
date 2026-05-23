DO $$ BEGIN
  CREATE TYPE "public"."enum_route_release_batches_status" AS ENUM('draft', 'scheduled', 'active', 'completed', 'cancelled');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;
--> statement-breakpoint
DO $$ BEGIN
  CREATE TYPE "public"."enum_route_release_routes_status" AS ENUM('open', 'claimed', 'cancelled');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;
--> statement-breakpoint
DO $$ BEGIN
  CREATE TYPE "public"."enum_route_job_offers_status" AS ENUM('pending', 'accepted', 'declined', 'expired', 'cancelled');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS "route_release_batches" (
  "id" serial PRIMARY KEY,
  "service_area_id" integer NOT NULL,
  "name" varchar(255) NOT NULL,
  "community_name" varchar(255) NOT NULL,
  "eligible_zip_codes" jsonb NOT NULL,
  "status" "public"."enum_route_release_batches_status" NOT NULL DEFAULT 'draft',
  "release_at" timestamp with time zone NOT NULL,
  "next_wave_at" timestamp with time zone,
  "offer_window_minutes" integer NOT NULL DEFAULT 240,
  "timezone" varchar(64) NOT NULL DEFAULT 'America/Chicago',
  "daytime_window_start" varchar(5) NOT NULL DEFAULT '08:00',
  "daytime_window_end" varchar(5) NOT NULL DEFAULT '20:00',
  "cleanup_starts_at" timestamp with time zone,
  "cleanup_ends_at" timestamp with time zone,
  "target_slots" integer NOT NULL DEFAULT 0,
  "wave_number" integer NOT NULL DEFAULT 0,
  "created_by_id" integer,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS "route_release_routes" (
  "id" serial PRIMARY KEY,
  "batch_id" integer NOT NULL,
  "route_id" integer NOT NULL,
  "job_id" integer NOT NULL,
  "status" "public"."enum_route_release_routes_status" NOT NULL DEFAULT 'open',
  "accepted_by_worker_id" integer,
  "accepted_offer_id" integer,
  "accepted_at" timestamp with time zone,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS "route_job_offers" (
  "id" serial PRIMARY KEY,
  "batch_id" integer NOT NULL,
  "worker_id" integer NOT NULL,
  "wave_number" integer NOT NULL,
  "status" "public"."enum_route_job_offers_status" NOT NULL DEFAULT 'pending',
  "offer_token" varchar(96) NOT NULL,
  "offered_at" timestamp with time zone NOT NULL,
  "expires_at" timestamp with time zone NOT NULL,
  "responded_at" timestamp with time zone,
  "selected_route_id" integer,
  "selected_job_id" integer,
  "decline_reason" text,
  "notification_status" varchar(32) NOT NULL DEFAULT 'pending',
  "notification_last_error" text,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS "route_community_queue" (
  "id" serial PRIMARY KEY,
  "service_area_id" integer NOT NULL,
  "worker_id" integer NOT NULL,
  "queue_rank" numeric,
  "last_opportunity_at" timestamp with time zone,
  "last_offered_batch_id" integer,
  "last_offer_status" "public"."enum_route_job_offers_status",
  "opportunities_count" integer NOT NULL DEFAULT 0,
  "carryover_release_after" timestamp with time zone,
  "carryover_attempts" integer NOT NULL DEFAULT 0,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS "route_offer_notifications" (
  "id" serial PRIMARY KEY,
  "offer_id" integer NOT NULL,
  "worker_id" integer NOT NULL,
  "channel" varchar(20) NOT NULL,
  "kind" varchar(32) NOT NULL,
  "status" varchar(32) NOT NULL DEFAULT 'pending',
  "scheduled_at" timestamp with time zone NOT NULL,
  "sent_at" timestamp with time zone,
  "attempts" integer NOT NULL DEFAULT 0,
  "last_error" text,
  "payload" jsonb,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS "idx_route_release_batches_service_area" ON "route_release_batches" ("service_area_id");
CREATE INDEX IF NOT EXISTS "idx_route_release_batches_status_next_wave" ON "route_release_batches" ("status", "next_wave_at");
CREATE UNIQUE INDEX IF NOT EXISTS "idx_route_release_routes_batch_route_unique" ON "route_release_routes" ("batch_id", "route_id");
CREATE UNIQUE INDEX IF NOT EXISTS "idx_route_release_routes_job_unique" ON "route_release_routes" ("job_id");
CREATE INDEX IF NOT EXISTS "idx_route_release_routes_batch_status" ON "route_release_routes" ("batch_id", "status");
CREATE UNIQUE INDEX IF NOT EXISTS "idx_route_job_offers_token_unique" ON "route_job_offers" ("offer_token");
CREATE INDEX IF NOT EXISTS "idx_route_job_offers_batch_worker" ON "route_job_offers" ("batch_id", "worker_id");
CREATE INDEX IF NOT EXISTS "idx_route_job_offers_status_expiry" ON "route_job_offers" ("status", "expires_at");
CREATE UNIQUE INDEX IF NOT EXISTS "idx_route_community_queue_service_worker_unique" ON "route_community_queue" ("service_area_id", "worker_id");
CREATE INDEX IF NOT EXISTS "idx_route_community_queue_service_opportunity" ON "route_community_queue" ("service_area_id", "last_opportunity_at");
CREATE UNIQUE INDEX IF NOT EXISTS "idx_route_offer_notifications_offer_channel_kind_unique" ON "route_offer_notifications" ("offer_id", "channel", "kind");
CREATE INDEX IF NOT EXISTS "idx_route_offer_notifications_status_scheduled" ON "route_offer_notifications" ("status", "scheduled_at");
CREATE INDEX IF NOT EXISTS "idx_route_offer_notifications_worker" ON "route_offer_notifications" ("worker_id");
