-- Add material donations table for tracking supply/equipment donations
DO $$ BEGIN
    CREATE TYPE "enum_material_donations_status" AS ENUM('pending', 'contacted', 'scheduled', 'received', 'cancelled');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

CREATE TABLE IF NOT EXISTS "material_donations" (
    "id" serial PRIMARY KEY,
    "name" varchar(100) NOT NULL,
    "email" varchar(255) NOT NULL,
    "phone" varchar(20),
    "company" varchar(100),
    "items" text NOT NULL,
    "item_categories" jsonb,
    "quantity" varchar(100),
    "condition" varchar(50),
    "preferred_method" varchar(20),
    "address" text,
    "available_dates" text,
    "notes" text,
    "status" "enum_material_donations_status" DEFAULT 'pending' NOT NULL,
    "admin_notes" text,
    "scheduled_date" timestamp with time zone,
    "received_date" timestamp with time zone,
    "estimated_value" numeric,
    "created_at" timestamp with time zone DEFAULT now() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT now() NOT NULL
);

-- Add index for status filtering
CREATE INDEX IF NOT EXISTS "material_donations_status_idx" ON "material_donations" ("status");
CREATE INDEX IF NOT EXISTS "material_donations_created_at_idx" ON "material_donations" ("created_at" DESC);
