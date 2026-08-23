import { neon } from '@neondatabase/serverless'

async function main() {
  const url = process.env.DATABASE_URL || process.env.DATABASE_URI
  if (!url) throw new Error('DATABASE_URL not set')
  const sql = neon(url)

  const blocks: Array<{ label: string; stmt: string }> = [
    {
      label: 'enum donor_type',
      stmt: `DO $$ BEGIN
        CREATE TYPE "enum_in_kind_donations_donor_type" AS ENUM('organization', 'business', 'individual', 'government');
      EXCEPTION WHEN duplicate_object THEN null; END $$;`,
    },
    {
      label: 'enum status',
      stmt: `DO $$ BEGIN
        CREATE TYPE "enum_in_kind_donations_status" AS ENUM('received', 'acknowledged', 'archived');
      EXCEPTION WHEN duplicate_object THEN null; END $$;`,
    },
    {
      label: 'enum ack_method',
      stmt: `DO $$ BEGIN
        CREATE TYPE "enum_in_kind_donations_ack_method" AS ENUM('email', 'mail', 'in_person');
      EXCEPTION WHEN duplicate_object THEN null; END $$;`,
    },
    {
      label: 'enum value_source',
      stmt: `DO $$ BEGIN
        CREATE TYPE "enum_in_kind_donations_value_source" AS ENUM('donor_provided', 'staff_estimate', 'appraisal', 'unknown');
      EXCEPTION WHEN duplicate_object THEN null; END $$;`,
    },
    {
      label: 'table in_kind_donations',
      stmt: `CREATE TABLE IF NOT EXISTS "in_kind_donations" (
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
      );`,
    },
    {
      label: 'table in_kind_donation_items',
      stmt: `CREATE TABLE IF NOT EXISTS "in_kind_donation_items" (
        "id" serial PRIMARY KEY,
        "donation_id" integer NOT NULL REFERENCES "in_kind_donations"("id") ON DELETE CASCADE,
        "description" varchar(300) NOT NULL,
        "quantity" integer NOT NULL DEFAULT 1,
        "unit_label" varchar(50),
        "condition" varchar(50),
        "estimated_unit_value_cents" integer,
        "sort_order" integer NOT NULL DEFAULT 0,
        "created_at" timestamp with time zone NOT NULL DEFAULT now()
      );`,
    },
    { label: 'idx status', stmt: `CREATE INDEX IF NOT EXISTS "in_kind_donations_status_idx" ON "in_kind_donations" ("status");` },
    {
      label: 'idx donation_date',
      stmt: `CREATE INDEX IF NOT EXISTS "in_kind_donations_donation_date_idx" ON "in_kind_donations" ("donation_date" DESC);`,
    },
    {
      label: 'idx donor_name',
      stmt: `CREATE INDEX IF NOT EXISTS "in_kind_donations_donor_name_idx" ON "in_kind_donations" ("donor_name");`,
    },
    {
      label: 'idx items.donation_id',
      stmt: `CREATE INDEX IF NOT EXISTS "in_kind_donation_items_donation_id_idx" ON "in_kind_donation_items" ("donation_id");`,
    },
  ]

  for (const { label, stmt } of blocks) {
    try {
      await sql.query(stmt)
      console.log('OK  :', label)
    } catch (e) {
      const msg = (e as Error).message
      if (/already exists|duplicate object/i.test(msg)) {
        console.log('SKIP:', label, '(already exists)')
      } else {
        console.error('FAIL:', label, '->', msg)
        throw e
      }
    }
  }

  console.log('\nMigration 0013 applied to production DB.')
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
