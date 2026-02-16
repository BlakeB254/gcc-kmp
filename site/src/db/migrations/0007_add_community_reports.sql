-- Add community reports for "off the clock" volunteering feature
-- Migration: 0007_add_community_reports.sql

-- Create enum types for community reports
DO $$ BEGIN
    CREATE TYPE "public"."enum_community_reports_type" AS ENUM('sighting', 'volunteer_cleanup');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE "public"."enum_community_reports_status" AS ENUM('pending', 'verified', 'resolved', 'acknowledged', 'rejected');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

-- Create community_reports table
CREATE TABLE IF NOT EXISTS "community_reports" (
    "id" SERIAL PRIMARY KEY,

    -- Reporter (polymorphic - can be worker, sponsor, or anonymous)
    "reporter_type" "enum_user_type",
    "reporter_id" INTEGER,
    "reporter_name" VARCHAR,

    -- Report type and details
    "type" "enum_community_reports_type" NOT NULL,
    "title" VARCHAR(255),
    "description" TEXT,

    -- Location
    "coordinates" JSONB NOT NULL,
    "location_address" VARCHAR,
    "location_neighborhood" VARCHAR,
    "service_area_id" INTEGER,
    "near_block_id" INTEGER,

    -- Media evidence
    "media_ids" JSONB,
    "before_photos" JSONB,
    "after_photos" JSONB,

    -- Cleanup details (for volunteer_cleanup type)
    "cleanup_duration_minutes" INTEGER,
    "cleanup_bags_collected" INTEGER,
    "cleanup_notes" TEXT,

    -- Status and moderation
    "status" "enum_community_reports_status" NOT NULL DEFAULT 'pending',
    "reviewed_at" TIMESTAMP WITH TIME ZONE,
    "reviewed_by_id" INTEGER,
    "review_notes" TEXT,

    -- Map visibility
    "show_on_map" BOOLEAN DEFAULT true,
    "map_expires_at" TIMESTAMP WITH TIME ZONE,

    -- Resolution
    "resolved_by_job_id" INTEGER,

    -- Engagement
    "view_count" INTEGER DEFAULT 0,
    "thank_count" INTEGER DEFAULT 0,

    -- Timestamps
    "captured_at" TIMESTAMP WITH TIME ZONE,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    "updated_at" TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

-- Create community_report_thanks table
CREATE TABLE IF NOT EXISTS "community_report_thanks" (
    "id" SERIAL PRIMARY KEY,
    "report_id" INTEGER NOT NULL,
    "user_type" "enum_user_type" NOT NULL,
    "user_id" INTEGER NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

-- Create indexes for efficient querying
CREATE INDEX IF NOT EXISTS "idx_community_reports_type" ON "community_reports" ("type");
CREATE INDEX IF NOT EXISTS "idx_community_reports_status" ON "community_reports" ("status");
CREATE INDEX IF NOT EXISTS "idx_community_reports_reporter" ON "community_reports" ("reporter_type", "reporter_id");
CREATE INDEX IF NOT EXISTS "idx_community_reports_service_area" ON "community_reports" ("service_area_id");
CREATE INDEX IF NOT EXISTS "idx_community_reports_show_on_map" ON "community_reports" ("show_on_map", "status");
CREATE INDEX IF NOT EXISTS "idx_community_reports_created_at" ON "community_reports" ("created_at" DESC);

CREATE INDEX IF NOT EXISTS "idx_community_report_thanks_report" ON "community_report_thanks" ("report_id");
CREATE INDEX IF NOT EXISTS "idx_community_report_thanks_user" ON "community_report_thanks" ("user_type", "user_id");

-- Add comment for documentation
COMMENT ON TABLE "community_reports" IS 'Community sightings and volunteer cleanup reports for the "off the clock" feature';
COMMENT ON TABLE "community_report_thanks" IS 'Track community members thanking volunteers for their cleanup efforts';
