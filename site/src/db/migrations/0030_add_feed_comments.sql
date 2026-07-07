-- Hand-written migration (see CLAUDE.md: drizzle-kit generate is not trusted
-- until the snapshot chain is repaired; db:push against schema.ts is the
-- canonical deploy mechanism and this file is the recorded artifact).
--
-- Feed comments + per-post profile opt-in. Table shapes are ported as-is
-- from the retired Ktor server's V202603031_feed_social_tables.sql (server/
-- is being deleted in a sibling hardening task) because that design already
-- matches the mobile client's contract exactly:
--   FeedViewModel.postComment()  -> GccApiClient.addFeedComment()
--   FeedViewModel.openComments() -> GccApiClient.getFeedComments()
--   FeedViewModel.optInProfile() -> GccApiClient.optInFeedProfile()
CREATE TABLE IF NOT EXISTS "feed_comments" (
	"id" serial PRIMARY KEY NOT NULL,
	"feed_item_id" varchar(255) NOT NULL,
	"author_id" integer NOT NULL REFERENCES "workers"("id") ON DELETE CASCADE,
	"author_name" varchar(255) NOT NULL,
	"text" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS "idx_feed_comments_feed_item" ON "feed_comments" ("feed_item_id");
CREATE INDEX IF NOT EXISTS "idx_feed_comments_author" ON "feed_comments" ("author_id");
CREATE INDEX IF NOT EXISTS "idx_feed_comments_created" ON "feed_comments" ("created_at" DESC);

-- Per-feed-item, per-worker opt-in CONSENT row: "worker X consents to be
-- visible on item Y". Uniqueness is COMPOSITE (feed_item_id, worker_id) —
-- NOT unique on feed_item_id alone, which would let any authenticated
-- worker squat the single opt-in slot for someone else's feed item and
-- permanently block the real actor's opt-in (IDOR, flagged in security
-- review of PR #117).
--
-- Render contract (for the future real feed GET): an item's
-- actorProfileVisible = EXISTS(row WHERE feed_item_id = item.id AND
-- worker_id = item's actor id). A stranger's consent row is inert — it must
-- never flip visibility for an item they don't own. Never use
-- "any-row-exists" semantics.
CREATE TABLE IF NOT EXISTS "feed_profile_opt_ins" (
	"id" serial PRIMARY KEY NOT NULL,
	"feed_item_id" varchar(255) NOT NULL,
	"worker_id" integer NOT NULL REFERENCES "workers"("id") ON DELETE CASCADE,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "feed_profile_opt_ins_item_worker_unique" UNIQUE ("feed_item_id", "worker_id")
);

CREATE INDEX IF NOT EXISTS "idx_feed_profile_opt_ins_worker" ON "feed_profile_opt_ins" ("worker_id");
