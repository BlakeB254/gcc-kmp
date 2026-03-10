-- Add on-device AI analysis, capture attestation, and duplicate override fields
ALTER TABLE community_reports ADD COLUMN IF NOT EXISTS capture_attestation jsonb;
ALTER TABLE community_reports ADD COLUMN IF NOT EXISTS on_device_ai_result jsonb;
ALTER TABLE community_reports ADD COLUMN IF NOT EXISTS duplicate_override_reason text;
