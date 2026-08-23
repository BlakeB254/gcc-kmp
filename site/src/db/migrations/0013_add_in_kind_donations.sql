-- Add in-kind donation acknowledgment records (received gifts, not intake inquiries).
-- Parallel to sponsor_deliverable_packages but for non-cash gifts of goods, equipment, or services.

DO $$ BEGIN
    CREATE TYPE "enum_in_kind_donations_donor_type" AS ENUM('organization', 'business', 'individual', 'government');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE "enum_in_kind_donations_status" AS ENUM('received', 'acknowledged', 'archived');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE "enum_in_kind_donations_ack_method" AS ENUM('email', 'mail', 'in_person');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE "enum_in_kind_donations_value_source" AS ENUM('donor_provided', 'staff_estimate', 'appraisal', 'unknown');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

CREATE TABLE IF NOT EXISTS "in_kind_donations" (
    "id" serial PRIMARY KEY,
    "slug" varchar(100) NOT NULL UNIQUE,
    "is_template" boolean NOT NULL DEFAULT false,

    "donor_name" varchar(200) NOT NULL,
    "donor_type" "enum_in_kind_donations_donor_type" NOT NULL DEFAULT 'individual',
    "donor_contact_name" varchar(100),
    "donor_email" varchar(255),
    "donor_phone" varchar(30),
    "donor_address_street" varchar(200),
    "donor_address_city" varchar(100),
    "donor_address_state" varchar(50),
    "donor_address_zip" varchar(20),

    "donation_date" varchar(20) NOT NULL,
    "received_date" varchar(20),
    "drop_off_location" varchar(200),

    "estimated_value_cents" integer,
    "value_source" "enum_in_kind_donations_value_source" NOT NULL DEFAULT 'unknown',
    "value_notes" text,

    "storage_location" varchar(200),
    "intended_use" text,
    "program_fund" varchar(100),

    "status" "enum_in_kind_donations_status" NOT NULL DEFAULT 'received',
    "acknowledged_at" timestamp with time zone,
    "acknowledgment_method" "enum_in_kind_donations_ack_method",
    "signing_officer_name" varchar(100),
    "signing_officer_title" varchar(100),
    "thank_you_message" text,

    "notes" text,
    "created_by_worker_id" integer,
    "created_at" timestamp with time zone NOT NULL DEFAULT now(),
    "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS "in_kind_donation_items" (
    "id" serial PRIMARY KEY,
    "donation_id" integer NOT NULL REFERENCES "in_kind_donations"("id") ON DELETE CASCADE,
    "description" varchar(300) NOT NULL,
    "quantity" integer NOT NULL DEFAULT 1,
    "unit_label" varchar(50),
    "condition" varchar(50),
    "estimated_unit_value_cents" integer,
    "sort_order" integer NOT NULL DEFAULT 0,
    "created_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS "in_kind_donations_status_idx" ON "in_kind_donations" ("status");
CREATE INDEX IF NOT EXISTS "in_kind_donations_donation_date_idx" ON "in_kind_donations" ("donation_date" DESC);
CREATE INDEX IF NOT EXISTS "in_kind_donations_donor_name_idx" ON "in_kind_donations" ("donor_name");
CREATE INDEX IF NOT EXISTS "in_kind_donation_items_donation_id_idx" ON "in_kind_donation_items" ("donation_id");
