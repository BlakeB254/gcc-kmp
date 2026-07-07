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

-- Per-feed-item opt-in: a worker reveals their profile on one specific feed
-- event, not a blanket account-wide setting — feed_item_id is UNIQUE so a
-- second opt-in call on the same item is a no-op (ON CONFLICT DO NOTHING in
-- the route handler).
CREATE TABLE IF NOT EXISTS "feed_profile_opt_ins" (
	"id" serial PRIMARY KEY NOT NULL,
	"feed_item_id" varchar(255) NOT NULL UNIQUE,
	"worker_id" integer NOT NULL REFERENCES "workers"("id") ON DELETE CASCADE,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX IF NOT EXISTS "idx_feed_profile_opt_ins_worker" ON "feed_profile_opt_ins" ("worker_id");
