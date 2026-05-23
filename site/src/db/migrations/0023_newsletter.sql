CREATE TABLE "email_unsubscribes" (
	"id" serial PRIMARY KEY NOT NULL,
	"email" varchar NOT NULL,
	"reason" varchar,
	"unsubscribed_from" varchar,
	"unsubscribed_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "email_unsubscribes_email_unique" UNIQUE("email")
);
--> statement-breakpoint
CREATE TABLE "newsletter_sends" (
	"id" serial PRIMARY KEY NOT NULL,
	"issue_slug" varchar NOT NULL,
	"subject" varchar NOT NULL,
	"recipient_count" integer NOT NULL,
	"success_count" integer NOT NULL,
	"failure_count" integer NOT NULL,
	"failed_recipients" jsonb,
	"triggered_by_worker_id" integer,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone
);
