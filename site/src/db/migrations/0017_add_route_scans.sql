CREATE TABLE IF NOT EXISTS "route_scans" (
  "id" serial PRIMARY KEY,
  "route_id" integer NOT NULL,
  "worker_id" integer NOT NULL,
  "device_type" varchar(10) NOT NULL DEFAULT 'gps',
  "phase" varchar(30) NOT NULL DEFAULT 'scouting',
  "coverage_percent" double precision NOT NULL DEFAULT 0,
  "scout_started_at" timestamp with time zone NOT NULL DEFAULT now(),
  "scout_completed_at" timestamp with time zone,
  "clean_started_at" timestamp with time zone,
  "clean_completed_at" timestamp with time zone,
  "start_lat" double precision NOT NULL,
  "start_lng" double precision NOT NULL,
  "scout_path_geojson" text,
  "clean_path_geojson" text,
  "total_trash_volume_liters" double precision,
  "total_trash_weight_lbs" double precision,
  "points_awarded" integer NOT NULL DEFAULT 0,
  "status" varchar(30) NOT NULL DEFAULT 'in_progress',
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS "scan_hotspots" (
  "id" serial PRIMARY KEY,
  "scan_id" integer NOT NULL,
  "lat" double precision NOT NULL,
  "lng" double precision NOT NULL,
  "trash_types" varchar(500) NOT NULL DEFAULT '[]',
  "severity" integer NOT NULL DEFAULT 1,
  "estimated_volume_liters" double precision,
  "estimated_weight_lbs" double precision,
  "confidence" double precision NOT NULL DEFAULT 0,
  "before_photo_url" varchar(500) NOT NULL,
  "after_photo_url" varchar(500),
  "ar_anchor_data" text,
  "depth_data" text,
  "detected_at" timestamp with time zone NOT NULL DEFAULT now(),
  "cleaned_at" timestamp with time zone,
  "verification_score" double precision,
  "status" varchar(20) NOT NULL DEFAULT 'detected'
);

CREATE INDEX IF NOT EXISTS "idx_route_scans_route" ON "route_scans" ("route_id");
CREATE INDEX IF NOT EXISTS "idx_route_scans_worker" ON "route_scans" ("worker_id");
CREATE INDEX IF NOT EXISTS "idx_route_scans_status" ON "route_scans" ("status");
CREATE INDEX IF NOT EXISTS "idx_scan_hotspots_scan" ON "scan_hotspots" ("scan_id");
