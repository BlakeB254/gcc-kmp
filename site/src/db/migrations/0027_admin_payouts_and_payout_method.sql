-- Admin-initiated ad-hoc payouts + worker payout-method preference.
-- Spec: docs/superpowers/specs/2026-07-06-admin-payouts-design.md
-- Same pattern as 0025/0026: apply directly to live Neon via psql; this
-- file documents the DDL.

-- Shared by workers.payout_method (preference) and payouts.method (snapshot).
CREATE TYPE enum_payout_method AS ENUM ('stripe', 'check');

-- Stripe path: processing -> completed/failed (webhook-reconciled).
-- Check path: check_pending -> check_mailed (manual fulfillment).
CREATE TYPE enum_payouts_status AS ENUM (
  'processing',
  'completed',
  'failed',
  'check_pending',
  'check_mailed'
);

CREATE TABLE IF NOT EXISTS payouts (
  id serial PRIMARY KEY,
  worker_id integer NOT NULL,
  amount_cents integer NOT NULL,
  note varchar,
  method enum_payout_method NOT NULL,
  status enum_payouts_status NOT NULL,
  stripe_transfer_id varchar,
  stripe_payout_id varchar,
  initiated_by_admin_id integer NOT NULL,
  idempotency_key varchar NOT NULL,
  mailing_address jsonb,
  check_mailed_at timestamp with time zone,
  check_mailed_by_admin_id integer,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS payouts_worker_id_idx ON payouts (worker_id);
CREATE INDEX IF NOT EXISTS payouts_status_idx ON payouts (status);
CREATE UNIQUE INDEX IF NOT EXISTS payouts_idempotency_key_unique ON payouts (idempotency_key);

ALTER TABLE workers ADD COLUMN IF NOT EXISTS payout_method enum_payout_method DEFAULT 'stripe';
ALTER TABLE workers ADD COLUMN IF NOT EXISTS payout_mailing_address jsonb;
