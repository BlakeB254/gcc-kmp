-- Add 'community_report' to media category enum
-- Migration: 0008_add_community_report_media_category.sql

DO $$ BEGIN
    ALTER TYPE "enum_media_category" ADD VALUE IF NOT EXISTS 'community_report';
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;
