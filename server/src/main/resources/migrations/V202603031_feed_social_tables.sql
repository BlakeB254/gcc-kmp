-- Feed Social Tables Migration
-- Created: 2026-03-31
-- Purpose: Comments, captions, and profile opt-ins for the community feed

CREATE TABLE IF NOT EXISTS feed_comments (
    id SERIAL PRIMARY KEY,
    feed_item_id VARCHAR(255) NOT NULL,
    author_id INTEGER NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    author_name VARCHAR(255) NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feed_comments_feed_item ON feed_comments(feed_item_id);
CREATE INDEX idx_feed_comments_author ON feed_comments(author_id);
CREATE INDEX idx_feed_comments_created ON feed_comments(created_at DESC);

CREATE TABLE IF NOT EXISTS feed_captions (
    id SERIAL PRIMARY KEY,
    feed_item_id VARCHAR(255) NOT NULL UNIQUE,
    worker_id INTEGER NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    caption TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feed_captions_worker ON feed_captions(worker_id);

CREATE TABLE IF NOT EXISTS feed_profile_opt_ins (
    id SERIAL PRIMARY KEY,
    feed_item_id VARCHAR(255) NOT NULL UNIQUE,
    worker_id INTEGER NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feed_profile_opt_ins_worker ON feed_profile_opt_ins(worker_id);
