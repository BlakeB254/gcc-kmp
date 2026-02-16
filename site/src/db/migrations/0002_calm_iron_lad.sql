CREATE TYPE "public"."enum_feedback_tickets_category" AS ENUM('safety_concern', 'app_issue', 'verification_problem', 'payment_issue', 'general_improvement', 'feature_request', 'other');--> statement-breakpoint
CREATE TYPE "public"."enum_feedback_tickets_priority" AS ENUM('low', 'medium', 'high', 'urgent');--> statement-breakpoint
CREATE TYPE "public"."enum_feedback_tickets_status" AS ENUM('open', 'in_progress', 'waiting_response', 'resolved', 'closed');--> statement-breakpoint
CREATE TYPE "public"."enum_forum_posts_category" AS ENUM('general', 'worker_tips', 'sponsor_stories', 'feature_requests', 'announcements', 'local_news');--> statement-breakpoint
CREATE TYPE "public"."enum_forum_posts_status" AS ENUM('active', 'hidden', 'locked', 'archived');--> statement-breakpoint
CREATE TYPE "public"."enum_user_type" AS ENUM('worker', 'sponsor');--> statement-breakpoint
CREATE TABLE "feedback_replies" (
	"id" serial PRIMARY KEY NOT NULL,
	"ticket_id" integer NOT NULL,
	"is_admin_reply" boolean DEFAULT false NOT NULL,
	"replier_name" varchar NOT NULL,
	"replier_email" varchar,
	"message" text NOT NULL,
	"is_internal" boolean DEFAULT false,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "feedback_tickets" (
	"id" serial PRIMARY KEY NOT NULL,
	"user_type" "enum_user_type" NOT NULL,
	"user_id" integer NOT NULL,
	"user_email" varchar NOT NULL,
	"user_name" varchar NOT NULL,
	"category" "enum_feedback_tickets_category" NOT NULL,
	"subject" varchar(255) NOT NULL,
	"description" text NOT NULL,
	"status" "enum_feedback_tickets_status" DEFAULT 'open' NOT NULL,
	"priority" "enum_feedback_tickets_priority" DEFAULT 'medium' NOT NULL,
	"assigned_to" varchar,
	"resolution_notes" text,
	"resolved_at" timestamp with time zone,
	"attachment_ids" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "forum_likes" (
	"id" serial PRIMARY KEY NOT NULL,
	"user_type" "enum_user_type" NOT NULL,
	"user_id" integer NOT NULL,
	"post_id" integer,
	"reply_id" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "forum_posts" (
	"id" serial PRIMARY KEY NOT NULL,
	"author_type" "enum_user_type" NOT NULL,
	"author_id" integer NOT NULL,
	"author_name" varchar NOT NULL,
	"category" "enum_forum_posts_category" DEFAULT 'general' NOT NULL,
	"title" varchar(255) NOT NULL,
	"content" text NOT NULL,
	"status" "enum_forum_posts_status" DEFAULT 'active' NOT NULL,
	"is_pinned" boolean DEFAULT false,
	"is_announcement" boolean DEFAULT false,
	"view_count" integer DEFAULT 0,
	"reply_count" integer DEFAULT 0,
	"like_count" integer DEFAULT 0,
	"last_activity_at" timestamp with time zone DEFAULT now(),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "forum_replies" (
	"id" serial PRIMARY KEY NOT NULL,
	"post_id" integer NOT NULL,
	"author_type" "enum_user_type" NOT NULL,
	"author_id" integer NOT NULL,
	"author_name" varchar NOT NULL,
	"parent_reply_id" integer,
	"content" text NOT NULL,
	"is_hidden" boolean DEFAULT false,
	"like_count" integer DEFAULT 0,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
