ALTER TABLE "blocks" ADD COLUMN "image_location_lat" double precision;--> statement-breakpoint
ALTER TABLE "blocks" ADD COLUMN "image_location_lng" double precision;--> statement-breakpoint
ALTER TABLE "community_reports" ADD COLUMN "geo_city" varchar;--> statement-breakpoint
ALTER TABLE "community_reports" ADD COLUMN "geo_state" varchar;--> statement-breakpoint
ALTER TABLE "community_reports" ADD COLUMN "geo_community_area" varchar;--> statement-breakpoint
ALTER TABLE "routes" ADD COLUMN "image_location_lat" double precision;--> statement-breakpoint
ALTER TABLE "routes" ADD COLUMN "image_location_lng" double precision;--> statement-breakpoint
ALTER TABLE "workers" ADD COLUMN "display_handle" varchar;--> statement-breakpoint
ALTER TABLE "workers" ADD COLUMN "member_number" integer;--> statement-breakpoint
ALTER TABLE "workers" ADD CONSTRAINT "workers_display_handle_unique" UNIQUE("display_handle");