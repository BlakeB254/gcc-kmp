CREATE TYPE "public"."enum_route_checkpoints_type" AS ENUM('route_start', 'photo_point', 'alley_entrance', 'route_end');--> statement-breakpoint
CREATE TYPE "public"."enum_routes_status" AS ENUM('draft', 'published', 'archived');--> statement-breakpoint
CREATE TYPE "public"."enum_service_areas_status" AS ENUM('planning', 'pilot', 'active', 'paused');--> statement-breakpoint
CREATE TABLE "route_checkpoints" (
	"id" serial PRIMARY KEY NOT NULL,
	"route_id" integer NOT NULL,
	"name" varchar(100) NOT NULL,
	"sequence_order" integer NOT NULL,
	"coordinates" jsonb NOT NULL,
	"photo_required" boolean DEFAULT true,
	"photo_instructions" text,
	"geofence_radius" numeric DEFAULT '15',
	"type" "enum_route_checkpoints_type" NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "routes" (
	"id" serial PRIMARY KEY NOT NULL,
	"code" varchar(20) NOT NULL,
	"display_name" varchar(255) NOT NULL,
	"service_area_id" integer NOT NULL,
	"geometry" jsonb NOT NULL,
	"length_meters" numeric,
	"estimated_minutes" numeric,
	"status" "enum_routes_status" DEFAULT 'draft',
	"published_at" timestamp with time zone,
	"version" integer DEFAULT 1,
	"payout_amount" numeric DEFAULT '10000',
	"created_by_id" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "routes_code_unique" UNIQUE("code")
);
--> statement-breakpoint
CREATE TABLE "service_areas" (
	"id" serial PRIMARY KEY NOT NULL,
	"name" varchar(255) NOT NULL,
	"city" varchar(100) NOT NULL,
	"state" varchar(50) NOT NULL,
	"boundary" jsonb NOT NULL,
	"map_center" jsonb NOT NULL,
	"default_zoom" numeric DEFAULT '14',
	"status" "enum_service_areas_status" DEFAULT 'planning',
	"launch_date" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
