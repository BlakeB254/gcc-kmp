-- 0055: at most one ACTIVE PAID job per block, and per worker.
--
-- The site talks to Neon over neon-http: every statement is its own
-- transaction, so "check, then write" in the app can't keep two workers off
-- one block, or one worker off two blocks. Every path that starts work now
-- takes the block and the job in one statement (site/src/lib/job-lifecycle.ts
-- takeBlockForJob); these indexes are the database's guarantee behind it — a
-- second active paid job fails with unique_violation, which the routes answer
-- as 409.
--
-- Active = claimed / in_progress / pending_review. $0 test runs are exempt:
-- several testers may run one route, and a tester may hold a test run while
-- doing paid work. The worker app shows one active job; no product doc or
-- flow intends one worker holding two paid jobs.
--
-- Prod check before writing this (2026-10-19, read-only): 0 active jobs and
-- no duplicates, so both indexes build without conflicts.
CREATE UNIQUE INDEX IF NOT EXISTS jobs_one_active_paid_job_per_block
  ON jobs (block_id)
  WHERE status IN ('claimed', 'in_progress', 'pending_review')
    AND coalesce(payout_amount, 0) > 0;

CREATE UNIQUE INDEX IF NOT EXISTS jobs_one_active_paid_job_per_worker
  ON jobs (worker_id)
  WHERE status IN ('claimed', 'in_progress', 'pending_review')
    AND coalesce(payout_amount, 0) > 0
    AND worker_id IS NOT NULL;
