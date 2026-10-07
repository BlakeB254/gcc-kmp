-- Stripe webhook retry idempotency for sponsor payments (sec/standard-money-flow).
--
-- The webhook releases its event claim when a handler throws, so Stripe's retry
-- re-runs the whole handler. The transactions insert is now
-- INSERT … ON CONFLICT DO NOTHING, with the sponsor stats increment and block
-- funding in the same statement and applied only for rows it created
-- (site/src/lib/stripe/sponsor-payment-events.ts). These unique keys are what
-- make the re-run a no-op.
--
-- Idempotent: safe to run more than once. Additive: deletes nothing.
-- Apply BEFORE deploying the code: the webhook names stripe_invoice_id in its
-- inserts, and full-row selects of transactions name every schema column.

ALTER TABLE "transactions" ADD COLUMN IF NOT EXISTS "stripe_invoice_id" varchar;

-- One row per checkout session per block. block_id NULL is the session's
-- general-donation row (key 0). A session that funds N blocks inserts N rows
-- with N different block ids; the legacy single-blockId path adds one
-- (session, block) row next to the general row.
--
-- If existing rows already break the key, the index is NOT created (the
-- migration must not fail and no data is deleted): a NOTICE lists the
-- duplicate ids for the owner to resolve, then re-run this file.
DO $$
DECLARE
  dup record;
  found boolean := false;
BEGIN
  FOR dup IN
    SELECT stripe_checkout_session_id, coalesce(block_id, 0) AS block_key, array_agg(id ORDER BY id) AS ids
    FROM "transactions"
    WHERE stripe_checkout_session_id IS NOT NULL
    GROUP BY stripe_checkout_session_id, coalesce(block_id, 0)
    HAVING count(*) > 1
  LOOP
    found := true;
    RAISE NOTICE 'duplicate transactions for session % block %: ids %',
      dup.stripe_checkout_session_id, dup.block_key, dup.ids;
  END LOOP;

  IF found THEN
    RAISE NOTICE 'transactions_checkout_session_block_unique NOT created: resolve the duplicates above, then re-run';
  ELSE
    EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS "transactions_checkout_session_block_unique" '
         || 'ON "transactions" ("stripe_checkout_session_id", (coalesce(block_id, 0))) '
         || 'WHERE stripe_checkout_session_id IS NOT NULL';
  END IF;
END $$;

-- One row per renewal invoice. The column is new, so no existing row can clash.
CREATE UNIQUE INDEX IF NOT EXISTS "transactions_stripe_invoice_id_unique"
  ON "transactions" ("stripe_invoice_id")
  WHERE stripe_invoice_id IS NOT NULL;
