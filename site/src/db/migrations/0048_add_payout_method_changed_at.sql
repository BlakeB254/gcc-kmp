-- Standard money flow (sec/standard-money-flow): cash outs are held for 24h
-- after a worker changes where their money goes. Nullable, no default — NULL
-- means "never changed", which does not hold anything.
--
-- MUST be applied before the code that reads it is deployed: workers.* selects
-- (db.select().from(workers)) name every schema column and fail on a missing one.
ALTER TABLE "workers" ADD COLUMN IF NOT EXISTS "payout_method_changed_at" timestamp with time zone;
