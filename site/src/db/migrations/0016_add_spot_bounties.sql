CREATE TABLE IF NOT EXISTS "bounties" (
  "id" serial PRIMARY KEY NOT NULL,
  "poster_type" varchar(20) DEFAULT 'worker' NOT NULL,
  "poster_id" integer,
  "wallet_id" integer DEFAULT 0 NOT NULL,
  "title" varchar(255) NOT NULL,
  "description" text,
  "amount" numeric DEFAULT '0' NOT NULL,
  "status" varchar(32) DEFAULT 'open' NOT NULL,
  "visibility" varchar(20) DEFAULT 'public' NOT NULL,
  "is_anonymous" boolean DEFAULT false NOT NULL,
  "poster_name" varchar(255),
  "location_lat" double precision NOT NULL,
  "location_lng" double precision NOT NULL,
  "radius_meters" numeric DEFAULT '200' NOT NULL,
  "city" varchar(100) DEFAULT 'Chicago' NOT NULL,
  "community_area" varchar,
  "neighborhood" varchar,
  "trigger_report_id" integer,
  "observer_id" integer,
  "first_refusal_expires_at" timestamp with time zone,
  "claimed_by" integer,
  "job_id" integer,
  "media_urls" jsonb,
  "completion_media_urls" jsonb,
  "ai_analysis" jsonb,
  "pledge_count" integer DEFAULT 1 NOT NULL,
  "spotter_tip_amount" numeric,
  "spotter_tip_paid_at" timestamp with time zone,
  "expires_at" timestamp with time zone,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS "idx_bounties_trigger_report_id" ON "bounties" ("trigger_report_id");
CREATE INDEX IF NOT EXISTS "idx_bounties_status" ON "bounties" ("status");
CREATE INDEX IF NOT EXISTS "idx_bounties_location" ON "bounties" ("location_lat", "location_lng");
