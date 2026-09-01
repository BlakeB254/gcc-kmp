-- Raw-capture R2 key on scan coverage events (admin scan-viz needs to link
-- each coverage event to the blob it uploaded; see rawScanKey() in
-- site/src/lib/r2.ts). Null until the device confirms the presigned upload.
-- Guarded + additive; applied via psql with a ledger insert (the drizzle-kit
-- generate path is untrusted — see CLAUDE.md migration notes).

ALTER TABLE "scan_coverage_events"
    ADD COLUMN IF NOT EXISTS "raw_r2_key" varchar(500);
