-- Chicago 311 civic loop (docs/superpowers/specs/
-- 2026-07-10-chicago-311-civic-loop-design.md §3). Hand-written (drizzle-kit
-- generate untrusted past 0015); apply via psql + tracking row.

CREATE TABLE IF NOT EXISTS civic_reports (
  id                 serial PRIMARY KEY,
  sr_number          varchar(50) NOT NULL,
  sr_type            varchar(100) NOT NULL,
  status             varchar(50) NOT NULL,
  owner_department   varchar(100),
  created_date       timestamptz,
  last_modified_date timestamptz,
  closed_date        timestamptz,
  street_address     varchar(200),
  community_area     integer,
  lat                double precision,
  lng                double precision,
  location_cell_id   varchar(32),
  first_captured_at  timestamptz,
  synced_at          timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS civic_reports_sr_number_key
  ON civic_reports (sr_number);
CREATE INDEX IF NOT EXISTS civic_reports_cell_idx
  ON civic_reports (location_cell_id);
CREATE INDEX IF NOT EXISTS civic_reports_status_type_idx
  ON civic_reports (status, sr_type);
CREATE INDEX IF NOT EXISTS civic_reports_community_area_idx
  ON civic_reports (community_area);

CREATE TABLE IF NOT EXISTS civic_report_evidence (
  id               serial PRIMARY KEY,
  sr_number        varchar(50) NOT NULL,
  session_id       varchar(64) NOT NULL,
  observation_id   integer,
  photo_r2_key     varchar(500),
  worker_id        integer,
  match_confidence varchar(8) NOT NULL DEFAULT 'user',
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS civic_report_evidence_sr_session_key
  ON civic_report_evidence (sr_number, session_id);
CREATE INDEX IF NOT EXISTS civic_report_evidence_sr_idx
  ON civic_report_evidence (sr_number);

-- Points action for the civic-capture bonus (awarded only when
-- CIVIC_CAPTURE_BONUS_POINTS is configured; see spec §5.4).
ALTER TYPE enum_points_ledger_action ADD VALUE IF NOT EXISTS 'civic_capture';
