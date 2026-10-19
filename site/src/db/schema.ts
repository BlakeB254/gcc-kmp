import {
  pgTable,
  serial,
  bigserial,
  varchar,
  char,
  text,
  boolean,
  timestamp,
  numeric,
  integer,
  bigint,
  jsonb,
  pgEnum,
  doublePrecision,
  real,
  index,
  uniqueIndex,
  date,
  primaryKey,
} from 'drizzle-orm/pg-core'
import { sql } from 'drizzle-orm'

// Enums (matching Payload's generated enums)
export const earlyAdopterTierEnum = pgEnum('enum_workers_early_adopter_tier', [
  'founder',
  'pioneer',
  'early',
  'standard',
])

export const verificationStatusEnum = pgEnum('enum_workers_verification_status', [
  'pending',
  'approved',
  'rejected',
  'needs_info',
])

export const documentStatusEnum = pgEnum('enum_workers_verification_government_id_status', [
  'pending',
  'approved',
  'rejected',
  'resubmit',
])

export const proofStatusEnum = pgEnum('enum_workers_verification_proof_of_address_status', [
  'pending',
  'approved',
  'rejected',
  'resubmit',
])

export const stripeOnboardingStatusEnum = pgEnum('enum_workers_stripe_onboarding_status', [
  'not_started',
  'in_progress',
  'completed',
  'restricted',
])

export const pendingAddressStatusEnum = pgEnum('enum_workers_pending_address_change_status', [
  'none',
  'pending',
  'approved',
  'rejected',
])

// Shared by workers.payout_method (the worker's preference) and payouts.method
// (the method snapshotted onto each payout at send time).
export const payoutMethodEnum = pgEnum('enum_payout_method', [
  'stripe',
  'check',
])

export const payoutStatusEnum = pgEnum('enum_payouts_status', [
  // Stripe path: processing → completed/failed (reconciled by the payout.paid/
  // payout.failed webhooks, mirroring jobs.payoutStatus semantics)
  'processing',
  'completed',
  'failed',
  // Check path: check_pending (in the admin "checks to mail" queue) → check_mailed
  'check_pending',
  'check_mailed',
])

export const waitlistStatusEnum = pgEnum('enum_waitlist_entries_status', [
  'waitlisted',
  'invited',
  'converted',
])

export const waitlistTierEnum = pgEnum('enum_waitlist_entries_early_adopter_tier', [
  'founder',
  'pioneer',
  'early',
  'standard',
])

export const mediaCategoryEnum = pgEnum('enum_media_category', [
  'job_photo',
  'worker_id',
  'proof_of_address',
  'sponsor_logo',
  'landing',
  'tax_document',
  'community_report',  // Off the clock volunteer photos/videos
  'other',
])

// Workers table
export const workers = pgTable('workers', {
  id: serial('id').primaryKey(),
  name: varchar('name').notNull(),
  email: varchar('email').notNull().unique(),
  phone: varchar('phone').notNull(),

  // Email verification
  emailVerified: boolean('email_verified').default(false),
  emailVerificationToken: varchar('email_verification_token'),
  emailVerificationExpires: timestamp('email_verification_expires', { mode: 'string', withTimezone: true }),
  emailVerifiedAt: timestamp('email_verified_at', { mode: 'string', withTimezone: true }),

  // Social auth provider identifiers
  appleUserId: varchar('apple_user_id').unique(),

  // Address
  addressStreet: varchar('address_street').default('').notNull(),
  addressCity: varchar('address_city').default('').notNull(),
  addressState: varchar('address_state').default('').notNull(),
  addressZip: varchar('address_zip').default('').notNull(),
  addressNeighborhood: varchar('address_neighborhood'), // Chicago community area name
  addressIsNorthLawndale: boolean('address_is_north_lawndale'),

  // Pending address change
  pendingAddressChangeStreet: varchar('pending_address_change_street'),
  pendingAddressChangeCity: varchar('pending_address_change_city'),
  pendingAddressChangeState: varchar('pending_address_change_state'),
  pendingAddressChangeZip: varchar('pending_address_change_zip'),
  pendingAddressChangeProofOfAddressId: integer('pending_address_change_proof_of_address_id'),
  pendingAddressChangeRequestedAt: timestamp('pending_address_change_requested_at', { mode: 'string', withTimezone: true }),
  pendingAddressChangeStatus: pendingAddressStatusEnum('pending_address_change_status').default('none'),
  pendingAddressChangeReviewNotes: varchar('pending_address_change_review_notes'),
  lastAddressChangeAt: timestamp('last_address_change_at', { mode: 'string', withTimezone: true }),

  // Verification documents
  verificationGovernmentIdId: integer('verification_government_id_id'),
  verificationGovernmentIdStatus: documentStatusEnum('verification_government_id_status').default('pending'),
  verificationGovernmentIdNotes: varchar('verification_government_id_notes'),
  verificationProofOfAddressId: integer('verification_proof_of_address_id'),
  verificationProofOfAddressStatus: proofStatusEnum('verification_proof_of_address_status').default('pending'),
  verificationProofOfAddressNotes: varchar('verification_proof_of_address_notes'),
  verificationStatus: verificationStatusEnum('verification_status').default('pending'),
  verificationReviewNotes: varchar('verification_review_notes'),
  verificationResubmissionCount: numeric('verification_resubmission_count').default('0'),
  verificationLastResubmittedAt: timestamp('verification_last_resubmitted_at', { mode: 'string', withTimezone: true }),
  verificationReviewedAt: timestamp('verification_reviewed_at', { mode: 'string', withTimezone: true }),
  verificationReviewedById: integer('verification_reviewed_by_id'),

  // Stripe
  stripeAccountId: varchar('stripe_account_id'),
  stripeOnboardingStatus: stripeOnboardingStatusEnum('stripe_onboarding_status').default('not_started'),
  stripeInstantPayoutsEnabled: boolean('stripe_instant_payouts_enabled').default(false),
  stripeDefaultPayoutMethod: varchar('stripe_default_payout_method').default('standard'),

  // Payout preference: how cash-outs reach this worker.
  // 'check' payouts mail to payoutMailingAddress when set, else the home
  // address fields above.
  payoutMethod: payoutMethodEnum('payout_method').default('stripe'),
  payoutMailingAddress: jsonb('payout_mailing_address'), // { name, street, city, state, zip }
  // Last change to where cash outs go (method, mailing override, new Connect
  // account). Cash outs are held for 24h after it — see lib/withdraw-guards.ts.
  payoutMethodChangedAt: timestamp('payout_method_changed_at', { mode: 'string', withTimezone: true }),

  // In-app wallet: the worker's available balance. Authoritative for spend
  // checks — every mutation is a single conditional UPDATE (neon-http has no
  // interactive transactions), with workerWalletEntries as the audit ledger.
  // Funds credited here stay in the platform Stripe balance until the worker
  // cashes out; the ledger row is the internal earmark of that liability.
  walletBalanceCents: integer('wallet_balance_cents').default(0).notNull(),

  // Stats
  statsJobsCompleted: numeric('stats_jobs_completed').default('0'),
  statsTotalEarnings: numeric('stats_total_earnings').default('0'),
  statsAverageRating: numeric('stats_average_rating'),
  statsLastJobAt: timestamp('stats_last_job_at', { mode: 'string', withTimezone: true }),
  statsCommunityPoints: integer('stats_community_points').default(0),

  // Movement rankings
  signupTimestamp: timestamp('signup_timestamp', { mode: 'string', withTimezone: true }),
  signupRankingsGlobal: numeric('signup_rankings_global'),
  signupRankingsCountry: numeric('signup_rankings_country'),
  signupRankingsState: numeric('signup_rankings_state'),
  signupRankingsCity: numeric('signup_rankings_city'),
  signupRankingsNeighborhood: numeric('signup_rankings_neighborhood'), // Chicago community area rank
  earlyAdopterTier: earlyAdopterTierEnum('early_adopter_tier').default('standard'),
  referralCode: varchar('referral_code').unique(),

  // Viral card
  displayHandle: varchar('display_handle').unique(),
  memberNumber: integer('member_number'),

  referredBy: varchar('referred_by'),
  convertedFromWaitlistId: integer('converted_from_waitlist_id'),

  // Notifications
  notificationsPushEnabled: boolean('notifications_push_enabled').default(false),
  notificationsPushSubscription: jsonb('notifications_push_subscription'),
  notificationsSmsEnabled: boolean('notifications_sms_enabled').default(true),
  notificationsEmailEnabled: boolean('notifications_email_enabled').default(true),

  // Auth (Payload style)
  salt: varchar('salt'),
  hash: varchar('hash'),
  resetPasswordToken: varchar('reset_password_token'),
  resetPasswordExpiration: timestamp('reset_password_expiration', { mode: 'string', withTimezone: true }),
  loginAttempts: numeric('login_attempts').default('0'),
  lockUntil: timestamp('lock_until', { mode: 'string', withTimezone: true }),

  // Pending email change (account settings; email only switches after the new
  // address confirms a 6-digit code — token column mirrors the
  // emailVerificationToken "token:code" encoding)
  pendingEmail: varchar('pending_email'),
  pendingEmailToken: varchar('pending_email_token'),
  pendingEmailExpires: timestamp('pending_email_expires', { mode: 'string', withTimezone: true }),
  pendingEmailAttempts: integer('pending_email_attempts').default(0),

  // Two-factor authentication (TOTP authenticator app + recovery codes)
  totpSecret: varchar('totp_secret'),
  twoFactorEnabled: boolean('two_factor_enabled').default(false),
  twoFactorRecoveryCodes: jsonb('two_factor_recovery_codes'),
  twoFactorEnabledAt: timestamp('two_factor_enabled_at', { mode: 'string', withTimezone: true }),

  // Session revocation: every mobile session JWT carries the version it was
  // minted at (`tv` claim); verifyMobileToken rejects a mismatch. Bumped on
  // password change/reset and on logout-all. Migration 0047.
  tokenVersion: integer('token_version').default(0).notNull(),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Waitlist entries table
export const waitlistEntries = pgTable('waitlist_entries', {
  id: serial('id').primaryKey(),
  email: varchar('email').notNull().unique(),
  name: varchar('name'),
  phone: varchar('phone'),

  // Location
  locationCountry: varchar('location_country').default('United States').notNull(),
  locationCountryCode: varchar('location_country_code').default('US').notNull(),
  locationState: varchar('location_state').notNull(),
  locationStateCode: varchar('location_state_code').notNull(),
  locationCity: varchar('location_city').notNull(),
  locationNeighborhood: varchar('location_neighborhood'),

  // Rankings
  signupTimestamp: timestamp('signup_timestamp', { mode: 'string', withTimezone: true }).notNull(),
  rankingsGlobal: numeric('rankings_global').notNull(),
  rankingsCountry: numeric('rankings_country').notNull(),
  rankingsState: numeric('rankings_state').notNull(),
  rankingsCity: numeric('rankings_city').notNull(),
  rankingsNeighborhood: numeric('rankings_neighborhood'),

  // Tier and referral
  earlyAdopterTier: waitlistTierEnum('early_adopter_tier').default('standard').notNull(),
  referralCode: varchar('referral_code').notNull().unique(),
  referredById: integer('referred_by_id'),
  referralCount: numeric('referral_count').default('0'),

  // Status
  status: waitlistStatusEnum('status').default('waitlisted').notNull(),
  convertedToWorkerId: integer('converted_to_worker_id'),
  invitedAt: timestamp('invited_at', { mode: 'string', withTimezone: true }),

  // Source tracking (e.g., 'mobile-app', 'landing', 'referral')
  source: varchar('source'),
  // Mobile app platform preference (if source is 'mobile-app')
  mobileAppPlatform: varchar('mobile_app_platform'), // 'ios', 'android', 'both'

  // Preferences
  preferencesWantsToBeWorker: boolean('preferences_wants_to_be_worker').default(true),
  preferencesWantsToBeSponsor: boolean('preferences_wants_to_be_sponsor').default(false),
  preferencesWantsToVolunteer: boolean('preferences_wants_to_volunteer').default(false),
  preferencesEmailUpdates: boolean('preferences_email_updates').default(true),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Media table (for file uploads)
export const media = pgTable('media', {
  id: serial('id').primaryKey(),
  alt: varchar('alt'),
  category: mediaCategoryEnum('category'),
  url: varchar('url'),
  thumbnailUrl: varchar('thumbnail_u_r_l'),
  filename: varchar('filename').unique(),
  mimeType: varchar('mime_type'),
  filesize: numeric('filesize'),
  width: numeric('width'),
  height: numeric('height'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Sponsor enums
export const sponsorTypeEnum = pgEnum('enum_sponsors_type', [
  'individual',
  'business',
  'organization',
  'foundation',
])

export const sponsorFrequencyEnum = pgEnum('enum_sponsors_preferences_frequency', [
  'one_time',
  'monthly',
  'quarterly',
  'annual',
])

// Fund allocation type - how sponsor wants their money used
export const fundAllocationEnum = pgEnum('enum_sponsors_fund_allocation', [
  'general_fund',        // GCC uses for any available blocks
  'community_specific',  // Only used in sponsor's preferred community
])

// Sponsors table
export const sponsors = pgTable('sponsors', {
  id: serial('id').primaryKey(),
  name: varchar('name').notNull(),
  email: varchar('email').notNull().unique(),
  phone: varchar('phone'),

  // Email verification
  emailVerified: boolean('email_verified').default(false),
  emailVerificationToken: varchar('email_verification_token'),
  emailVerificationExpires: timestamp('email_verification_expires', { mode: 'string', withTimezone: true }),
  emailVerifiedAt: timestamp('email_verified_at', { mode: 'string', withTimezone: true }),

  type: sponsorTypeEnum('type').default('individual'),

  // Business info (if type is business/organization)
  businessName: varchar('business_name'),
  businessWebsite: varchar('business_website'),
  businessLogoId: integer('business_logo_id'),

  // Address
  addressStreet: varchar('address_street'),
  addressCity: varchar('address_city'),
  addressState: varchar('address_state'),
  addressZip: varchar('address_zip'),

  // Preferences
  preferencesFrequency: sponsorFrequencyEnum('preferences_frequency').default('one_time'),
  preferencesIsAnonymous: boolean('preferences_is_anonymous').default(false),
  preferencesWantsUpdates: boolean('preferences_wants_updates').default(true),

  // Fund allocation preferences - flexible sponsorship model
  fundAllocation: fundAllocationEnum('fund_allocation').default('general_fund'),
  preferredCommunity: varchar('preferred_community'),        // City/area if community_specific
  waitForCommunityLaunch: boolean('wait_for_community_launch').default(false), // Hold funds until launch
  letGccChooseBlocks: boolean('let_gcc_choose_blocks').default(true),          // GCC selects blocks vs sponsor

  // Stripe
  stripeCustomerId: varchar('stripe_customer_id'),
  stripeSubscriptionId: varchar('stripe_subscription_id'),
  stripeDefaultPaymentMethod: varchar('stripe_default_payment_method'),

  // Stats
  statsTotalContributed: numeric('stats_total_contributed').default('0'),
  statsBlocksFunded: numeric('stats_blocks_funded').default('0'),
  statsLastContributionAt: timestamp('stats_last_contribution_at', { mode: 'string', withTimezone: true }),

  // Active sponsorship
  activeSponsorship: boolean('active_sponsorship').default(false),

  // Movement rankings (sponsors are part of the movement too!)
  signupTimestamp: timestamp('signup_timestamp', { mode: 'string', withTimezone: true }),
  signupRankingsGlobal: numeric('signup_rankings_global'),
  earlyAdopterTier: earlyAdopterTierEnum('early_adopter_tier').default('standard'),
  referralCode: varchar('referral_code').unique(),
  referredBy: varchar('referred_by'),

  // Public profile settings
  profileIsPublic: boolean('profile_is_public').default(true),
  profileTagline: varchar('profile_tagline'), // Short tagline like "Proud to support North Lawndale"
  profileBio: text('profile_bio'), // Longer description
  profileSocialTwitter: varchar('profile_social_twitter'), // @handle or full URL
  profileSocialLinkedIn: varchar('profile_social_linkedin'), // Full URL
  profileSocialInstagram: varchar('profile_social_instagram'), // @handle or full URL
  profileSocialWebsite: varchar('profile_social_website'), // Additional website beyond businessWebsite

  // Auth
  salt: varchar('salt'),
  hash: varchar('hash'),
  resetPasswordToken: varchar('reset_password_token'),
  resetPasswordExpiration: timestamp('reset_password_expiration', { mode: 'string', withTimezone: true }),
  loginAttempts: numeric('login_attempts').default('0'),
  lockUntil: timestamp('lock_until', { mode: 'string', withTimezone: true }),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Block status enum
export const blockStatusEnum = pgEnum('enum_blocks_status', [
  'available',
  'funded',
  'in_progress',
  'completed',
])

// Blocks table
export const blocks = pgTable('blocks', {
  id: serial('id').primaryKey(),
  code: varchar('code', { length: 20 }).unique(), // Block code e.g., "NL-N01"
  name: varchar('name').notNull(),
  description: text('description'),
  status: blockStatusEnum('status').default('available'),

  // Service area reference
  serviceAreaId: integer('service_area_id'), // FK to service_areas

  // Route reference (1:1 relationship - each block has one route)
  routeId: integer('route_id'), // FK to routes - auto-created when block is saved

  // Location
  locationNeighborhood: varchar('location_neighborhood'),
  locationBoundary: jsonb('location_boundary'), // GeoJSON polygon
  // Route-v2 inner boundary: private-property cutoff (GeoJSON polygon).
  // Between locationBoundary and this ring = the required scan corridor.
  innerBoundary: jsonb('inner_boundary'),
  locationCenter: jsonb('location_center'), // GeoJSON point for map centering

  // Display image (Vercel Blob URL set via admin app)
  displayImage: varchar('display_image'),

  // Image location (where the display photo was taken)
  imageLocationLat: doublePrecision('image_location_lat'),
  imageLocationLng: doublePrecision('image_location_lng'),

  // Block metadata
  streetName: varchar('street_name'),
  fromCorner: varchar('from_corner'),
  toCorner: varchar('to_corner'),
  side: varchar('side', { length: 10 }), // 'north' or 'south'
  includesAlley: boolean('includes_alley').default(true),

  // Funding
  fundingGoal: numeric('funding_goal').default('100'),
  fundingCurrent: numeric('funding_current').default('0'),
  fundedById: integer('funded_by_id'), // Sponsor ID
  fundedAt: timestamp('funded_at', { mode: 'string', withTimezone: true }),

  // Preferred cleaning schedule (set by sponsor, seen by workers)
  preferredCleaningDate: timestamp('preferred_cleaning_date', { mode: 'string', withTimezone: true }),
  preferredCleaningTimeStart: varchar('preferred_cleaning_time_start', { length: 5 }), // HH:MM format
  preferredCleaningTimeEnd: varchar('preferred_cleaning_time_end', { length: 5 }), // HH:MM format

  // Completion
  completedById: integer('completed_by_id'), // Worker ID
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Block exclusion zones — polygons marking private property or otherwise
// off-limits areas within a block, used to flag world observations that
// fall inside them (see isWithinExclusionZone in lib/world-observations.ts)
export const blockExclusionZones = pgTable('block_exclusion_zones', {
  id: serial('id').primaryKey(),
  blockId: integer('block_id').notNull(),
  polygon: jsonb('polygon').notNull(), // GeoJSON Polygon: { type: 'Polygon', coordinates: [[[lng,lat],...]] }
  label: varchar('label', { length: 255 }),
  createdByAdminId: integer('created_by_admin_id'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type BlockExclusionZoneRow = typeof blockExclusionZones.$inferSelect
export type NewBlockExclusionZoneRow = typeof blockExclusionZones.$inferInsert

// Block proposal review status — staging area for auto-generated block
// candidates. Nothing here is live/public; a row only becomes a real
// `blocks` row once an admin approves it (see /api/admin/block-proposals).
export const blockProposalStatusEnum = pgEnum('enum_block_proposals_status', [
  'pending',
  'approved',
  'rejected',
])

// Auto-generated block candidates awaiting admin review — e.g. blocks
// derived from real OpenStreetMap street/building data when expanding
// coverage beyond the hand-traced pilot blocks. Never read by the public
// site or the mobile app; purely an admin staging table, so (per the repo's
// schema-ownership policy) it has no Kotlin/Exposed mirror — same
// precedent as block_exclusion_zones, which the Ktor server also never
// queries.
export const blockProposals = pgTable('block_proposals', {
  id: serial('id').primaryKey(),
  status: blockProposalStatusEnum('status').default('pending').notNull(),

  // Same shape as the live `blocks` fields this will become on approval
  codeHint: varchar('code_hint', { length: 20 }),
  name: varchar('name').notNull(),
  locationNeighborhood: varchar('location_neighborhood'),
  locationBoundary: jsonb('location_boundary').notNull(), // GeoJSON polygon
  locationCenter: jsonb('location_center').notNull(), // GeoJSON point
  streetName: varchar('street_name'),
  fromCorner: varchar('from_corner'),
  toCorner: varchar('to_corner'),
  side: varchar('side', { length: 10 }),
  exclusionZones: jsonb('exclusion_zones'), // Array of { polygon, label } — becomes block_exclusion_zones rows on approval

  // Provenance
  source: varchar('source', { length: 40 }).default('osm_auto_generated').notNull(),
  generationBatch: varchar('generation_batch', { length: 100 }), // e.g. "north-lawndale-2026-07-03"
  sourceRefs: jsonb('source_refs'), // OSM way/node ids used, for traceability

  // Review
  reviewedByAdminId: integer('reviewed_by_admin_id'),
  reviewedAt: timestamp('reviewed_at', { mode: 'string', withTimezone: true }),
  rejectionReason: text('rejection_reason'),
  publishedBlockId: integer('published_block_id'), // set once approved and inserted into `blocks`

  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type BlockProposalRow = typeof blockProposals.$inferSelect
export type NewBlockProposalRow = typeof blockProposals.$inferInsert

// Job status enum
export const jobStatusEnum = pgEnum('enum_jobs_status', [
  'available',
  'claimed',
  'in_progress',
  'pending_review',
  'completed',
  'disputed',
])

// Jobs table
export const jobs = pgTable('jobs', {
  id: serial('id').primaryKey(),
  blockId: integer('block_id').notNull(),
  workerId: integer('worker_id'),
  status: jobStatusEnum('status').default('available'),

  // Claim info
  claimedAt: timestamp('claimed_at', { mode: 'string', withTimezone: true }),
  startedAt: timestamp('started_at', { mode: 'string', withTimezone: true }),
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),

  // Photos and evidence
  beforePhotos: jsonb('before_photos'), // Array of {url, capturedAt} (PATCH /api/jobs/:id/evidence); legacy rows: media ids — read via lib/job-evidence.ts
  afterPhotos: jsonb('after_photos'), // same shape as beforePhotos
  gpsPath: jsonb('gps_path'), // GeoJSON LineString

  // Payout
  payoutAmount: numeric('payout_amount'),
  payoutStatus: varchar('payout_status'),
  payoutStripeTransferId: varchar('payout_stripe_transfer_id'),
  payoutCompletedAt: timestamp('payout_completed_at', { mode: 'string', withTimezone: true }),

  // Rating
  rating: numeric('rating'),
  ratingNotes: text('rating_notes'),

  // Admin proxy execution audit
  performedByAdminId: integer('performed_by_admin_id').references(() => workers.id),

  // Review / approval (Feature C — same-day payout flow)
  reviewStatus: varchar('review_status', { length: 20 }),
  approvedByAdminId: integer('approved_by_admin_id').references(() => workers.id),
  deniedByAdminId: integer('denied_by_admin_id').references(() => workers.id),
  denyReason: text('deny_reason'),
  reviewedAt: timestamp('reviewed_at', { mode: 'string', withTimezone: true }),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),

  // Server-verified completion (migration 0050). The route scan that proved
  // this job's work; unique, so one scan can never complete two jobs.
  completionScanId: integer('completion_scan_id').references(() => routeScans.id),
  // Paid-job TTL policy (lib/paid-job-ttl.ts). Each stamp counts for the
  // current claim/run only — "already sent" means stamp >= claimed_at (start
  // reminder) or >= coalesce(started_at, claimed_at) (progress reminder,
  // stalled flag) — so a released-then-reclaimed job reminds its next worker.
  startReminderSentAt: timestamp('start_reminder_sent_at', { mode: 'string', withTimezone: true }),
  progressReminderSentAt: timestamp('progress_reminder_sent_at', { mode: 'string', withTimezone: true }),
  // Paid job in progress past 24h with no qualifying scan: flagged for an
  // admin (GET /api/admin/jobs shows it). Never auto-released.
  stalledAt: timestamp('stalled_at', { mode: 'string', withTimezone: true }),
}, (table) => ({
  // Hot-path indexes — added during the M-6 audit pass.
  // Postgres does NOT auto-index foreign-key columns; these are queried
  // constantly ("jobs for worker X", "jobs on block Y", filtered by status).
  workerIdx: index('jobs_worker_id_idx').on(table.workerId),
  blockIdx: index('jobs_block_id_idx').on(table.blockId),
  statusIdx: index('jobs_status_idx').on(table.status),
  completionScanUnique: uniqueIndex('jobs_completion_scan_id_unique').on(table.completionScanId),
}))

// Route release queues turn a set of funded routes into timed offer waves.
// Workers are eligible by verified proof-of-address residency in the release
// area's ZIP codes. North Lawndale launches with 60623/60624.
export const routeReleaseStatusEnum = pgEnum('enum_route_release_batches_status', [
  'draft',
  'scheduled',
  'active',
  'completed',
  'cancelled',
])

export const routeReleaseRouteStatusEnum = pgEnum('enum_route_release_routes_status', [
  'open',
  'claimed',
  'cancelled',
])

export const routeJobOfferStatusEnum = pgEnum('enum_route_job_offers_status', [
  'pending',
  'accepted',
  'declined',
  'expired',
  'cancelled',
])

export const routeReleaseBatches = pgTable('route_release_batches', {
  id: serial('id').primaryKey(),
  serviceAreaId: integer('service_area_id').notNull(),
  name: varchar('name', { length: 255 }).notNull(),
  communityName: varchar('community_name', { length: 255 }).notNull(),
  eligibleZipCodes: jsonb('eligible_zip_codes').notNull(),
  status: routeReleaseStatusEnum('status').default('draft').notNull(),

  // Worker offer timing. Cron/admin processing only sends waves inside this
  // local daytime window.
  releaseAt: timestamp('release_at', { mode: 'string', withTimezone: true }).notNull(),
  nextWaveAt: timestamp('next_wave_at', { mode: 'string', withTimezone: true }),
  offerWindowMinutes: integer('offer_window_minutes').default(240).notNull(),
  timezone: varchar('timezone', { length: 64 }).default('America/Chicago').notNull(),
  daytimeWindowStart: varchar('daytime_window_start', { length: 5 }).default('08:00').notNull(),
  daytimeWindowEnd: varchar('daytime_window_end', { length: 5 }).default('20:00').notNull(),

  // Optional cleanup window shown to workers after they accept.
  cleanupStartsAt: timestamp('cleanup_starts_at', { mode: 'string', withTimezone: true }),
  cleanupEndsAt: timestamp('cleanup_ends_at', { mode: 'string', withTimezone: true }),

  targetSlots: integer('target_slots').default(0).notNull(),
  waveNumber: integer('wave_number').default(0).notNull(),
  createdById: integer('created_by_id'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  serviceAreaIdx: index('idx_route_release_batches_service_area').on(table.serviceAreaId),
  statusNextWaveIdx: index('idx_route_release_batches_status_next_wave').on(table.status, table.nextWaveAt),
}))

export const routeReleaseRoutes = pgTable('route_release_routes', {
  id: serial('id').primaryKey(),
  batchId: integer('batch_id').notNull(),
  routeId: integer('route_id').notNull(),
  jobId: integer('job_id').notNull(),
  status: routeReleaseRouteStatusEnum('status').default('open').notNull(),
  // Explicit per-batch ordering (migration 0042); offer presentation and the
  // admin queue honor position ASC.
  position: integer('position').default(0).notNull(),
  acceptedByWorkerId: integer('accepted_by_worker_id'),
  acceptedOfferId: integer('accepted_offer_id'),
  acceptedAt: timestamp('accepted_at', { mode: 'string', withTimezone: true }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  batchRouteUnique: uniqueIndex('idx_route_release_routes_batch_route_unique').on(table.batchId, table.routeId),
  jobUnique: uniqueIndex('idx_route_release_routes_job_unique').on(table.jobId),
  batchStatusIdx: index('idx_route_release_routes_batch_status').on(table.batchId, table.status),
  batchPositionIdx: index('idx_route_release_routes_batch_position').on(table.batchId, table.position),
}))

export const routeJobOffers = pgTable('route_job_offers', {
  id: serial('id').primaryKey(),
  batchId: integer('batch_id').notNull(),
  workerId: integer('worker_id').notNull(),
  waveNumber: integer('wave_number').notNull(),
  status: routeJobOfferStatusEnum('status').default('pending').notNull(),
  offerToken: varchar('offer_token', { length: 96 }).notNull(),
  offeredAt: timestamp('offered_at', { mode: 'string', withTimezone: true }).notNull(),
  expiresAt: timestamp('expires_at', { mode: 'string', withTimezone: true }).notNull(),
  respondedAt: timestamp('responded_at', { mode: 'string', withTimezone: true }),
  selectedRouteId: integer('selected_route_id'),
  selectedJobId: integer('selected_job_id'),
  declineReason: text('decline_reason'),
  notificationStatus: varchar('notification_status', { length: 32 }).default('pending').notNull(),
  notificationLastError: text('notification_last_error'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  offerTokenUnique: uniqueIndex('idx_route_job_offers_token_unique').on(table.offerToken),
  batchWorkerIdx: index('idx_route_job_offers_batch_worker').on(table.batchId, table.workerId),
  statusExpiryIdx: index('idx_route_job_offers_status_expiry').on(table.status, table.expiresAt),
}))

export const routeCommunityQueue = pgTable('route_community_queue', {
  id: serial('id').primaryKey(),
  serviceAreaId: integer('service_area_id').notNull(),
  workerId: integer('worker_id').notNull(),
  queueRank: numeric('queue_rank'),
  lastOpportunityAt: timestamp('last_opportunity_at', { mode: 'string', withTimezone: true }),
  lastOfferedBatchId: integer('last_offered_batch_id'),
  lastOfferStatus: routeJobOfferStatusEnum('last_offer_status'),
  opportunitiesCount: integer('opportunities_count').default(0).notNull(),
  carryoverReleaseAfter: timestamp('carryover_release_after', { mode: 'string', withTimezone: true }),
  carryoverAttempts: integer('carryover_attempts').default(0).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  serviceWorkerUnique: uniqueIndex('idx_route_community_queue_service_worker_unique').on(table.serviceAreaId, table.workerId),
  serviceOpportunityIdx: index('idx_route_community_queue_service_opportunity').on(table.serviceAreaId, table.lastOpportunityAt),
}))

export const routeOfferNotifications = pgTable('route_offer_notifications', {
  id: serial('id').primaryKey(),
  offerId: integer('offer_id').notNull(),
  workerId: integer('worker_id').notNull(),
  channel: varchar('channel', { length: 20 }).notNull(), // email, push
  kind: varchar('kind', { length: 32 }).notNull(), // initial, reminder_1h, reminder_5m
  status: varchar('status', { length: 32 }).default('pending').notNull(),
  scheduledAt: timestamp('scheduled_at', { mode: 'string', withTimezone: true }).notNull(),
  sentAt: timestamp('sent_at', { mode: 'string', withTimezone: true }),
  attempts: integer('attempts').default(0).notNull(),
  lastError: text('last_error'),
  payload: jsonb('payload'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  offerChannelKindUnique: uniqueIndex('idx_route_offer_notifications_offer_channel_kind_unique').on(table.offerId, table.channel, table.kind),
  statusScheduledIdx: index('idx_route_offer_notifications_status_scheduled').on(table.status, table.scheduledAt),
  workerIdx: index('idx_route_offer_notifications_worker').on(table.workerId),
}))

// Transaction status enum
export const transactionStatusEnum = pgEnum('enum_transactions_status', [
  'pending',
  'completed',
  'failed',
  'refunded',
])

// Allocation type enum - how the contribution should be used
export const allocationTypeEnum = pgEnum('enum_transactions_allocation_type', [
  'general',   // General fund - GCC allocates where needed
  'block',     // Specific block sponsorship
  'area',      // Area-wide sponsorship (future)
])

// Message status enum - for moderating supporter messages on the wall
export const messageStatusEnum = pgEnum('enum_transactions_message_status', [
  'visible',   // Message is shown as-is
  'hidden',    // Message is completely hidden (offensive)
  'redacted',  // Message replaced with admin-provided text
])

// Transactions table (for Stripe payments - both donations and sponsorships)
export const transactions = pgTable('transactions', {
  id: serial('id').primaryKey(),
  sponsorId: integer('sponsor_id'),
  blockId: integer('block_id'), // null = general donation, set = block sponsorship
  jobId: integer('job_id'),

  // Stripe info
  stripePaymentIntentId: varchar('stripe_payment_intent_id'),
  stripeCheckoutSessionId: varchar('stripe_checkout_session_id'),
  stripeSubscriptionId: varchar('stripe_subscription_id'),
  // invoice.paid renewals: one row per Stripe invoice (unique index below), so
  // a webhook retry cannot record the same renewal twice. Null on every other row.
  stripeInvoiceId: varchar('stripe_invoice_id'),

  // Amount
  amount: numeric('amount').notNull(), // In cents
  currency: varchar('currency').default('usd'),

  // Status
  status: transactionStatusEnum('status').default('pending'),

  // Contribution type and allocation
  allocationType: allocationTypeEnum('allocation_type').default('general'),
  sponsorType: varchar('sponsor_type'), // 'one_time' or 'monthly'

  // Public display settings
  displayName: varchar('display_name'),    // Name/org to display publicly (can differ from sponsor name)
  isAnonymous: boolean('is_anonymous').default(false), // If true, don't show name publicly

  // Supporter message
  message: text('message'),
  messageStatus: messageStatusEnum('message_status').default('visible'),
  messageRedactedText: text('message_redacted_text'), // Admin-provided replacement text if redacted

  // Preferred cleaning schedule (set by sponsor)
  preferredCleaningDate: timestamp('preferred_cleaning_date', { mode: 'string', withTimezone: true }), // Required for sponsorships
  preferredCleaningTimeStart: varchar('preferred_cleaning_time_start', { length: 5 }), // HH:MM format, optional
  preferredCleaningTimeEnd: varchar('preferred_cleaning_time_end', { length: 5 }), // HH:MM format, optional

  // Block reservation: a block sponsorship paid while the blocks could not be
  // assigned (lib/sponsor-checkout.ts). Set on a `general` row with no blockId;
  // an admin allocates it from /admin/reservations. Null on every other row.
  reservedBlockCount: integer('reserved_block_count'),
  reservedBlockCodes: varchar('reserved_block_codes', { length: 500 }), // comma-separated, or null = GCC chooses
  reservationAllocatedAt: timestamp('reservation_allocated_at', { mode: 'string', withTimezone: true }),
  reservationNote: text('reservation_note'), // admin's note on how it was allocated

  // Timestamps
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  // Hot-path indexes — "all sponsorships for sponsor X", "all transactions
  // for block Y", status filters on the sponsor wall.
  sponsorIdx: index('transactions_sponsor_id_idx').on(table.sponsorId),
  blockIdx: index('transactions_block_id_idx').on(table.blockId),
  statusIdx: index('transactions_status_idx').on(table.status),
  // Webhook retry guards (migration 0049): the Stripe webhook inserts with ON
  // CONFLICT DO NOTHING and only counts stats / funds blocks for rows it
  // actually created (lib/stripe/sponsor-payment-events.ts). One row per
  // checkout session per block (block_id NULL = the session's general row) …
  checkoutSessionBlockUnique: uniqueIndex('transactions_checkout_session_block_unique')
    .on(table.stripeCheckoutSessionId, sql`coalesce(block_id, 0)`)
    .where(sql`stripe_checkout_session_id IS NOT NULL`),
  // … and one row per renewal invoice.
  stripeInvoiceUnique: uniqueIndex('transactions_stripe_invoice_id_unique')
    .on(table.stripeInvoiceId)
    .where(sql`stripe_invoice_id IS NOT NULL`),
}))

// Stripe webhook event ledger — one row per Stripe event id we have accepted
// for processing. The webhook handler inserts with ON CONFLICT DO NOTHING and
// treats "no row returned" as "already processed", so Stripe's at-least-once
// retries can't double-apply payments or double-increment stats.
export const stripeEvents = pgTable('stripe_events', {
  id: varchar('id', { length: 255 }).primaryKey(), // Stripe event id (evt_...)
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Bounties are funded progressions of community spot reports. A spot can
// start as a zero-dollar cleanup opportunity and accumulate pledged bounty
// value over time.
export const bounties = pgTable('bounties', {
  id: serial('id').primaryKey(),

  // Poster (currently mobile workers; sponsor support can reuse this table)
  posterType: varchar('poster_type', { length: 20 }).default('worker').notNull(),
  posterId: integer('poster_id'),
  walletId: integer('wallet_id').default(0).notNull(),

  // Public bounty details
  title: varchar('title', { length: 255 }).notNull(),
  description: text('description'),
  amount: numeric('amount').default('0').notNull(), // In cents
  status: varchar('status', { length: 32 }).default('open').notNull(),
  visibility: varchar('visibility', { length: 20 }).default('public').notNull(),
  isAnonymous: boolean('is_anonymous').default(false).notNull(),
  posterName: varchar('poster_name', { length: 255 }),

  // Location copied from the source spot at pledge time
  locationLat: doublePrecision('location_lat').notNull(),
  locationLng: doublePrecision('location_lng').notNull(),
  radiusMeters: numeric('radius_meters').default('200').notNull(),
  city: varchar('city', { length: 100 }).default('Chicago').notNull(),
  communityArea: varchar('community_area'),
  neighborhood: varchar('neighborhood'),

  // Source spot and optional worker/job linkage
  triggerReportId: integer('trigger_report_id'),
  // Amendment 2 (litter-truth): a bounty can instead be seeded from a
  // worldObservations row when the triggering scan produced no report row.
  // Exactly one of triggerReportId/triggerObservationId is set — enforced
  // at the API layer, not the schema (both stay nullable).
  triggerObservationId: integer('trigger_observation_id'),
  observerId: integer('observer_id'),
  firstRefusalExpiresAt: timestamp('first_refusal_expires_at', { mode: 'string', withTimezone: true }),
  claimedBy: integer('claimed_by'),
  jobId: integer('job_id'),

  // Evidence and derived analysis
  mediaUrls: jsonb('media_urls'),
  completionMediaUrls: jsonb('completion_media_urls'),
  aiAnalysis: jsonb('ai_analysis'),
  pledgeCount: integer('pledge_count').default(1).notNull(),
  spotterTipAmount: numeric('spotter_tip_amount'),
  spotterTipPaidAt: timestamp('spotter_tip_paid_at', { mode: 'string', withTimezone: true }),
  expiresAt: timestamp('expires_at', { mode: 'string', withTimezone: true }),

  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Sponsor package status enum
export const sponsorPackageStatusEnum = pgEnum('enum_sponsor_packages_status', [
  'draft',
  'quoted',
  'paid',
  'active',
  'completed',
  'cancelled',
])

// Enterprise tier enum
export const enterpriseTierEnum = pgEnum('enum_sponsor_packages_enterprise_tier', [
  'community_champion',
  'impact_partner',
  'documentary_sponsor',
  'movement_partner',
  'legacy_sponsor',
  'founding_partner',
])

// Sponsor packages table (for calculator-based sponsorships)
export const sponsorPackages = pgTable('sponsor_packages', {
  id: serial('id').primaryKey(),
  sponsorId: integer('sponsor_id'), // FK to sponsors table
  name: varchar('name'), // e.g., "Q1 2025 North Lawndale Campaign"

  // Base configuration
  blockCount: integer('block_count').notNull(),
  baseAmount: numeric('base_amount').notNull(), // Worker payout (100% of discounted base)
  isRecurring: boolean('is_recurring').default(false),
  recurringFrequency: varchar('recurring_frequency'), // 'monthly', 'quarterly', 'annual'

  // Add-ons stored as JSONB for flexibility
  // Format: { vests: true, equipment: true, blockSigns: false, ... }
  workerBrandingAddons: jsonb('worker_branding_addons'),
  equipmentBrandingAddons: jsonb('equipment_branding_addons'),
  streetAdvertisingAddons: jsonb('street_advertising_addons'),
  digitalPresenceAddons: jsonb('digital_presence_addons'),
  enhancedCleaningAddons: jsonb('enhanced_cleaning_addons'),
  seasonalServicesAddons: jsonb('seasonal_services_addons'),
  greenServicesAddons: jsonb('green_services_addons'),
  businessEngagementAddons: jsonb('business_engagement_addons'),

  // Events (array of event configurations)
  eventPackages: jsonb('event_packages'), // [{packageId: 'standard_crew', date: '2025-03-15', quantity: 1}]

  // Media packages
  photoPackage: varchar('photo_package'), // e.g., 'standard_photo'
  videoPackage: varchar('video_package'), // e.g., 'impact_story'

  // Calculated amounts (in cents)
  workerBrandingAmount: numeric('worker_branding_amount').default('0'),
  equipmentBrandingAmount: numeric('equipment_branding_amount').default('0'),
  streetAdvertisingAmount: numeric('street_advertising_amount').default('0'),
  digitalPresenceAmount: numeric('digital_presence_amount').default('0'),
  enhancedCleaningAmount: numeric('enhanced_cleaning_amount').default('0'),
  seasonalServicesAmount: numeric('seasonal_services_amount').default('0'),
  greenServicesAmount: numeric('green_services_amount').default('0'),
  businessEngagementAmount: numeric('business_engagement_amount').default('0'),
  eventsAmount: numeric('events_amount').default('0'),
  mediaAmount: numeric('media_amount').default('0'),

  // Totals
  addonsAmount: numeric('addons_amount').default('0'),
  volumeDiscountAmount: numeric('volume_discount_amount').default('0'),
  recurringDiscountAmount: numeric('recurring_discount_amount').default('0'),
  totalAmount: numeric('total_amount').notNull(),
  workerPayoutAmount: numeric('worker_payout_amount').notNull(), // Always transparent
  gccFeeAmount: numeric('gcc_fee_amount').default('0'), // Total - worker payout

  // Enterprise flags
  isEnterprise: boolean('is_enterprise').default(false),
  enterpriseTier: enterpriseTierEnum('enterprise_tier'),
  requiresCustomQuote: boolean('requires_custom_quote').default(false),
  recognitionLevel: varchar('recognition_level'), // 'supporter', 'friend', 'advocate', etc.

  // Status
  status: sponsorPackageStatusEnum('status').default('draft'),
  stripePaymentIntentId: varchar('stripe_payment_intent_id'),
  stripeCheckoutSessionId: varchar('stripe_checkout_session_id'),
  stripeSubscriptionId: varchar('stripe_subscription_id'),
  paidAt: timestamp('paid_at', { mode: 'string', withTimezone: true }),

  // Notes and metadata
  notes: text('notes'),
  metadata: jsonb('metadata'), // Additional custom data

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// =====================
// SPONSOR DELIVERABLES
// =====================
// Tracks the recognition deliverables owed to a sponsor at a given gift level.
// Triggered automatically by Stripe webhook for online payments at/above the
// $2,000 threshold, or created manually by an admin for offline payments
// (checks, wires, ACH).

export const sponsorDeliverableSourceEnum = pgEnum('enum_sponsor_deliverables_source', [
  'online',  // From Stripe checkout webhook
  'offline', // Manually entered by admin (check, wire, ACH, in-person)
])

export const sponsorDeliverableStatusEnum = pgEnum('enum_sponsor_deliverables_status', [
  'pending',
  'in_progress',
  'completed',
])

export const sponsorDeliverableItemKindEnum = pgEnum('enum_sponsor_deliverable_items_kind', [
  'recognition_wall',
  'certificate',
  'tax_receipt',
  'newsletter_feature',
  'social_recognition',
])

export const sponsorDeliverableItemStatusEnum = pgEnum('enum_sponsor_deliverable_items_status', [
  'pending',
  'completed',
])

// One row per qualifying gift. Holds the gift context plus aggregate status.
export const sponsorDeliverablePackages = pgTable('sponsor_deliverable_packages', {
  id: serial('id').primaryKey(),
  sponsorId: integer('sponsor_id').notNull(), // FK to sponsors

  // Linkage to the originating money — at most one of these will be set.
  transactionId: integer('transaction_id'),       // Set for online (Stripe) payments
  sponsorPackageId: integer('sponsor_package_id'), // Set if tied to a calculator package

  // Money + provenance
  amountCents: integer('amount_cents').notNull(), // Qualifying amount in cents
  source: sponsorDeliverableSourceEnum('source').notNull(),
  externalReference: varchar('external_reference'), // e.g., "UIC check #1234"

  // The tier label that drove the item set (matches sponsor-tiers.ts).
  tier: varchar('tier', { length: 20 }).notNull(), // 'silver' | 'gold' | 'platinum' | etc.

  status: sponsorDeliverableStatusEnum('status').default('pending').notNull(),

  // Free-form admin notes
  notes: text('notes'),

  // Audit
  createdByWorkerId: integer('created_by_worker_id'), // Admin worker who created it (offline)
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// One row per item inside a package (5 items at the $2K silver level).
export const sponsorDeliverableItems = pgTable('sponsor_deliverable_items', {
  id: serial('id').primaryKey(),
  packageId: integer('package_id').notNull(), // FK to sponsorDeliverablePackages

  kind: sponsorDeliverableItemKindEnum('kind').notNull(),
  status: sponsorDeliverableItemStatusEnum('status').default('pending').notNull(),

  // Per-item rendered output (certificate URL, receipt number, social copy, etc.)
  data: jsonb('data'),

  triggeredByWorkerId: integer('triggered_by_worker_id'),
  triggeredAt: timestamp('triggered_at', { mode: 'string', withTimezone: true }),

  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// =====================
// EMAIL UNSUBSCRIBES + NEWSLETTER SEND LOG
// =====================

// Single source of truth for opt-outs across all our subscriber surfaces
// (sponsors, waitlist, future newsletter signups). The /unsubscribe page
// upserts here; outbound sends LEFT JOIN against it and exclude matches.
export const emailUnsubscribes = pgTable('email_unsubscribes', {
  id: serial('id').primaryKey(),
  email: varchar('email').notNull().unique(),
  reason: varchar('reason'),               // free-text reason from user
  unsubscribedFrom: varchar('unsubscribed_from'), // 'newsletter' | 'all' | etc.
  unsubscribedAt: timestamp('unsubscribed_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// One row per outbound send so we can show send history in the admin
// and avoid double-sending the same issue to the same address.
export const newsletterSends = pgTable('newsletter_sends', {
  id: serial('id').primaryKey(),
  issueSlug: varchar('issue_slug').notNull(),  // e.g. '2026-05-01-issue-01'
  subject: varchar('subject').notNull(),
  recipientCount: integer('recipient_count').notNull(),
  successCount: integer('success_count').notNull(),
  failureCount: integer('failure_count').notNull(),
  failedRecipients: jsonb('failed_recipients'),  // [{ email, error }]
  triggeredByWorkerId: integer('triggered_by_worker_id'),
  startedAt: timestamp('started_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
})

// Mobile crash reports (iOS/Android). Public, unauthenticated ingest -- a
// crash can happen before login, so there's no worker/session to attach.
export const crashReports = pgTable('crash_reports', {
  id: serial('id').primaryKey(),
  deviceId: varchar('device_id').notNull(),
  platform: varchar('platform').notNull(),
  appVersion: varchar('app_version'),
  exceptionMessage: text('exception_message').notNull(),
  stackTrace: text('stack_trace').notNull(),
  occurredAt: timestamp('occurred_at', { mode: 'string', withTimezone: true }).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  // Full MetricKit diagnostic JSON in R2 (diagnostics/metrickit/…) when this
  // row came from POST /api/crashes/diagnostics; stackTrace then holds a
  // truncated inline snippet. Null for plain crash reports. Migration 0037.
  r2Key: varchar('r2_key', { length: 500 }),
}, (table) => ({
  // Per-device throttle check (POST /api/crashes) queries recent rows for a
  // device — without this, that's a sequential scan over the whole table.
  deviceCreatedIdx: index('crash_reports_device_id_created_at_idx').on(table.deviceId, table.createdAt),
}))

// =====================
// FEEDBACK & COMMUNITY
// =====================

// Feedback ticket category enum
export const feedbackCategoryEnum = pgEnum('enum_feedback_tickets_category', [
  'safety_concern',
  'app_issue',
  'verification_problem',
  'payment_issue',
  'general_improvement',
  'feature_request',
  'other',
])

// Feedback ticket status enum
export const feedbackStatusEnum = pgEnum('enum_feedback_tickets_status', [
  'open',
  'in_progress',
  'waiting_response',
  'resolved',
  'closed',
])

// Feedback ticket priority enum
export const feedbackPriorityEnum = pgEnum('enum_feedback_tickets_priority', [
  'low',
  'medium',
  'high',
  'urgent',
])

// User type enum (for polymorphic references)
export const userTypeEnum = pgEnum('enum_user_type', [
  'worker',
  'sponsor',
])

// Feedback tickets table
export const feedbackTickets = pgTable('feedback_tickets', {
  id: serial('id').primaryKey(),

  // User who submitted (polymorphic - either worker or sponsor)
  userType: userTypeEnum('user_type').notNull(),
  userId: integer('user_id').notNull(),
  userEmail: varchar('user_email').notNull(),
  userName: varchar('user_name').notNull(),

  // Ticket details
  category: feedbackCategoryEnum('category').notNull(),
  subject: varchar('subject', { length: 255 }).notNull(),
  description: text('description').notNull(),

  // Status tracking
  status: feedbackStatusEnum('status').default('open').notNull(),
  priority: feedbackPriorityEnum('priority').default('medium').notNull(),

  // Assignment
  assignedTo: varchar('assigned_to'), // Admin name/identifier

  // Resolution
  resolutionNotes: text('resolution_notes'),
  resolvedAt: timestamp('resolved_at', { mode: 'string', withTimezone: true }),

  // Attachments (array of media IDs)
  attachmentIds: jsonb('attachment_ids'),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Feedback replies table (admin responses)
export const feedbackReplies = pgTable('feedback_replies', {
  id: serial('id').primaryKey(),
  ticketId: integer('ticket_id').notNull(),

  // Who replied (either user or admin)
  isAdminReply: boolean('is_admin_reply').default(false).notNull(),
  replierName: varchar('replier_name').notNull(),
  replierEmail: varchar('replier_email'),

  // Reply content
  message: text('message').notNull(),

  // Internal notes (admin only, not visible to user)
  isInternal: boolean('is_internal').default(false),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Forum post category enum
export const forumCategoryEnum = pgEnum('enum_forum_posts_category', [
  'general',
  'worker_tips',
  'sponsor_stories',
  'feature_requests',
  'announcements',
  'local_news',
])

// Forum post status enum
export const forumStatusEnum = pgEnum('enum_forum_posts_status', [
  'active',
  'hidden',
  'locked',
  'archived',
])

// Forum posts table
export const forumPosts = pgTable('forum_posts', {
  id: serial('id').primaryKey(),

  // Author (polymorphic - either worker or sponsor)
  authorType: userTypeEnum('author_type').notNull(),
  authorId: integer('author_id').notNull(),
  authorName: varchar('author_name').notNull(),

  // Post content
  category: forumCategoryEnum('category').default('general').notNull(),
  title: varchar('title', { length: 255 }).notNull(),
  content: text('content').notNull(),

  // Status and moderation
  status: forumStatusEnum('status').default('active').notNull(),
  isPinned: boolean('is_pinned').default(false),
  isAnnouncement: boolean('is_announcement').default(false),

  // Engagement stats
  viewCount: integer('view_count').default(0),
  replyCount: integer('reply_count').default(0),
  likeCount: integer('like_count').default(0),

  // Last activity (for sorting by recent activity)
  lastActivityAt: timestamp('last_activity_at', { mode: 'string', withTimezone: true }).defaultNow(),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Forum replies table
export const forumReplies = pgTable('forum_replies', {
  id: serial('id').primaryKey(),
  postId: integer('post_id').notNull(),

  // Author (polymorphic)
  authorType: userTypeEnum('author_type').notNull(),
  authorId: integer('author_id').notNull(),
  authorName: varchar('author_name').notNull(),

  // Parent reply for threading (null = top-level reply)
  parentReplyId: integer('parent_reply_id'),

  // Reply content
  content: text('content').notNull(),

  // Moderation
  isHidden: boolean('is_hidden').default(false),

  // Engagement
  likeCount: integer('like_count').default(0),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Forum likes table (for tracking who liked what)
export const forumLikes = pgTable('forum_likes', {
  id: serial('id').primaryKey(),

  // User who liked
  userType: userTypeEnum('user_type').notNull(),
  userId: integer('user_id').notNull(),

  // What they liked (post or reply)
  postId: integer('post_id'),
  replyId: integer('reply_id'),

  // Timestamp
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// =====================
// COMMUNITY REPORTS ("Off the Clock" Volunteering)
// =====================

// Community report type enum
export const communityReportTypeEnum = pgEnum('enum_community_reports_type', [
  'sighting',       // User spotted an area that needs cleaning
  'volunteer_cleanup', // User cleaned an area voluntarily (off the clock)
])

// Community report status enum
export const communityReportStatusEnum = pgEnum('enum_community_reports_status', [
  'pending',        // Just submitted, awaiting review
  'verified',       // Admin verified the report
  'resolved',       // Issue has been addressed (for sightings)
  'acknowledged',   // Volunteer effort acknowledged
  'rejected',       // Invalid or spam report
])

// Community reports table
export const communityReports = pgTable('community_reports', {
  id: serial('id').primaryKey(),

  // Reporter (polymorphic - can be worker, sponsor, or anonymous)
  reporterType: userTypeEnum('reporter_type'), // null = anonymous
  reporterId: integer('reporter_id'),          // null = anonymous
  reporterName: varchar('reporter_name'),      // Display name (optional for anonymous)
  source: varchar('source', { length: 20 }),   // 'admin' | 'user' | 'seed' — admin spots don't award points

  // Report type and details
  type: communityReportTypeEnum('type').notNull(),
  title: varchar('title', { length: 255 }),    // Optional brief title
  description: text('description'),             // What they saw or what they cleaned

  // Location
  coordinates: jsonb('coordinates').notNull(), // [lng, lat] point
  locationAddress: varchar('location_address'), // Reverse-geocoded or user-entered address
  locationNeighborhood: varchar('location_neighborhood'),
  serviceAreaId: integer('service_area_id'),   // FK to service_areas (if within one)
  nearBlockId: integer('near_block_id'),       // Closest block (if applicable)

  // Media evidence (array of media IDs)
  mediaIds: jsonb('media_ids'),                // Photos and/or videos
  mediaCaptions: jsonb('media_captions'),      // Per-photo captions (parallel array to mediaIds)
  depthAnalysis: jsonb('depth_analysis'),       // LiDAR depth data (DepthAnalysis JSON)
  captureAttestation: jsonb('capture_attestation'), // Anti-fraud: device, GPS, timestamp, session
  onDeviceAiResult: jsonb('on_device_ai_result'),   // On-device AI analysis (trash types, severity, weight)
  duplicateOverrideReason: text('duplicate_override_reason'), // Why user overrode duplicate warning
  beforePhotos: jsonb('before_photos'),        // For volunteer_cleanup: before state
  afterPhotos: jsonb('after_photos'),          // For volunteer_cleanup: after state

  // Cleanup details (for volunteer_cleanup type)
  cleanupDurationMinutes: integer('cleanup_duration_minutes'),
  cleanupBagsCollected: integer('cleanup_bags_collected'),
  cleanupNotes: text('cleanup_notes'),

  // Geographic categorization (populated at creation via reverse geocoding)
  geoCity: varchar('geo_city'),
  geoState: varchar('geo_state'),
  geoCommunityArea: varchar('geo_community_area'), // Chicago-specific (77 community areas)
  geoCommunityAreaNumber: integer('geo_community_area_number'), // Official 1-77 number
  geoWard: integer('geo_ward'), // Chicago ward 1-50
  geoZipCode: varchar('geo_zip_code', { length: 10 }),
  geoCensusTract: varchar('geo_census_tract'), // Census tract ID
  geoPoliceDistrict: integer('geo_police_district'), // CPD district number
  geoStreetAddress: varchar('geo_street_address'), // Full street address

  // Affected radius in meters (for map display)
  affectedRadiusMeters: numeric('affected_radius_meters').default('15'),

  // Affected area geometry (circle or rectangle JSON)
  affectedArea: jsonb('affected_area'),

  // Spot tags (array of tag strings: trash, leaves, glass, etc.)
  tags: jsonb('tags'),

  // Status and moderation
  status: communityReportStatusEnum('status').default('pending').notNull(),
  reviewedAt: timestamp('reviewed_at', { mode: 'string', withTimezone: true }),
  reviewedById: integer('reviewed_by_id'),     // Admin who reviewed
  reviewNotes: text('review_notes'),

  // Map visibility
  showOnMap: boolean('show_on_map').default(true),
  mapExpiresAt: timestamp('map_expires_at', { mode: 'string', withTimezone: true }), // When to remove from map (for sightings)

  // Resolution (for sightings that led to actual block cleaning)
  resolvedByJobId: integer('resolved_by_job_id'), // If a paid job cleaned this up

  // Engagement
  viewCount: integer('view_count').default(0),
  thankCount: integer('thank_count').default(0), // Community "thanks" for volunteers

  // Verification lifecycle (24h re-verification cycle)
  lastVerifiedAt: timestamp('last_verified_at', { mode: 'string', withTimezone: true }),
  verificationCount: integer('verification_count').default(0),
  nextVerificationDue: timestamp('next_verification_due', { mode: 'string', withTimezone: true }),
  missedVerifications: integer('missed_verifications').default(0),
  maxMissedVerifications: integer('max_missed_verifications').default(3),

  // Timestamps
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }), // When photo/video was taken
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  // Hot-path indexes — "reports by worker X", "reports in service area Y",
  // status filtering for moderation/map display, near-block proximity joins.
  reporterIdx: index('community_reports_reporter_id_idx').on(table.reporterId),
  serviceAreaIdx: index('community_reports_service_area_id_idx').on(table.serviceAreaId),
  nearBlockIdx: index('community_reports_near_block_id_idx').on(table.nearBlockId),
  statusIdx: index('community_reports_status_idx').on(table.status),
}))

// Community report thanks table (track who thanked volunteers)
export const communityReportThanks = pgTable('community_report_thanks', {
  id: serial('id').primaryKey(),
  reportId: integer('report_id').notNull(),    // FK to community_reports

  // User who thanked
  userType: userTypeEnum('user_type').notNull(),
  userId: integer('user_id').notNull(),

  // Timestamp
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// =====================
// SPOT VERIFICATION LIFECYCLE
// =====================

// Spot verification result enum
export const spotVerificationResultEnum = pgEnum('enum_spot_verifications_result', [
  'still_dirty',    // Mess is still there
  'already_clean',  // Someone already cleaned it
])

// Points action enum
export const pointsActionEnum = pgEnum('enum_points_ledger_action', [
  'spot_created',           // Observer reported a new spot (10 pts)
  'verification_dirty',     // Verified spot is still dirty (5 pts)
  'verification_clean',     // Verified spot is already clean (10 pts)
  'volunteer_cleanup',      // Completed a volunteer cleanup (50 pts)
  'already_clean_report',   // Reported spot was already clean (5 pts)
  'admin_grant',            // Admin manually granted/corrected points
  'bug_report',             // Reward for finding a bug in the app/website
  'scan_coverage_new_area', // Distance-tracked: scanned a cell never scanned before
  'scan_coverage_stale',    // Distance-tracked: re-scanned a cell stale >24h
  'scan_coverage_close_upgrade', // Distance-tracked: grazed->close upgrade on a fresh cell
  'scan_coverage_rectification', // Confirmed a previously-expected-dirty spot is now clean
  'observation_logged',     // Scan recorded world observations (no spot report row) — 10 pts
  'civic_capture',          // Visual evidence attached to an open Chicago 311 report (bonus gated on CIVIC_CAPTURE_BONUS_POINTS)
])

// Spot verifications table (audit trail of each verification event)
export const spotVerifications = pgTable('spot_verifications', {
  id: serial('id').primaryKey(),
  reportId: integer('report_id').notNull(),         // FK to community_reports
  verifierId: integer('verifier_id').notNull(),      // Worker who verified
  verifierName: varchar('verifier_name'),

  // Result
  result: spotVerificationResultEnum('result').notNull(),
  photoUrl: varchar('photo_url'),                    // Verification evidence photo

  // GPS at verification time
  verifierLat: numeric('verifier_lat'),
  verifierLng: numeric('verifier_lng'),
  distanceFromSpotMeters: numeric('distance_from_spot_meters'),

  // Points
  pointsAwarded: integer('points_awarded').default(0),

  // Timestamp
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Points ledger table (immutable log of all points transactions)
export const pointsLedger = pgTable('points_ledger', {
  id: serial('id').primaryKey(),
  workerId: integer('worker_id').notNull(),          // FK to workers

  // Action
  action: pointsActionEnum('action').notNull(),
  points: integer('points').notNull(),

  // Polymorphic reference to what earned the points
  referenceType: varchar('reference_type'),          // 'spot', 'verification', 'cleanup'
  referenceId: integer('reference_id'),              // ID in the referenced table

  // Audit trail for admin-issued grants. NULL on auto-generated rows.
  reason: text('reason'),
  grantedByWorkerId: integer('granted_by_worker_id'),

  // Timestamp
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  // Hot-path indexes — leaderboard and worker profile both scan all points
  // for a worker. The reference (type+id) composite lets us answer "all
  // points earned for this spot" without scanning the whole ledger.
  workerIdx: index('points_ledger_worker_id_idx').on(table.workerId),
  referenceIdx: index('points_ledger_reference_idx').on(table.referenceType, table.referenceId),
}))

// =====================
// ROUTES & SERVICE AREAS
// =====================

// Service area status enum
export const serviceAreaStatusEnum = pgEnum('enum_service_areas_status', [
  'planning',
  'pilot',
  'active',
  'paused',
])

// Service areas table (for expansion management)
export const serviceAreas = pgTable('service_areas', {
  id: serial('id').primaryKey(),
  name: varchar('name', { length: 255 }).notNull(), // "North Lawndale"
  city: varchar('city', { length: 100 }).notNull(), // "Chicago"
  state: varchar('state', { length: 50 }).notNull(), // "IL"

  // GeoJSON Polygon defining the area boundary
  boundary: jsonb('boundary').notNull(),

  // Default map center [lng, lat]
  mapCenter: jsonb('map_center').notNull(),

  // Default zoom level
  defaultZoom: numeric('default_zoom').default('14'),

  // Status
  status: serviceAreaStatusEnum('status').default('planning'),

  // When the area launched/will launch
  launchDate: timestamp('launch_date', { mode: 'string', withTimezone: true }),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Route status enum
export const routeStatusEnum = pgEnum('enum_routes_status', [
  'draft',
  'published',
  'archived',
])

// Route claim status enum — independent from publish: publishing is the
// visibility switch, claim_status is the accepting-claims switch
// (migration 0042).
export const routeClaimStatusEnum = pgEnum('enum_routes_claim_status', [
  'closed',
  'open',
  'scheduled',
])

// Routes table (street-conforming cleaning paths)
export const routes = pgTable('routes', {
  id: serial('id').primaryKey(),
  code: varchar('code', { length: 20 }).notNull().unique(), // "NL-R001"
  displayName: varchar('display_name', { length: 255 }).notNull(),
  serviceAreaId: integer('service_area_id').notNull(), // FK to service_areas

  // GeoJSON LineString geometry (snapped to streets)
  geometry: jsonb('geometry').notNull(),

  // Route metrics
  lengthMeters: numeric('length_meters'),
  estimatedMinutes: numeric('estimated_minutes'),

  // Status & versioning
  status: routeStatusEnum('status').default('draft'),
  claimStatus: routeClaimStatusEnum('claim_status').default('closed'),
  publishedAt: timestamp('published_at', { mode: 'string', withTimezone: true }),
  // Scheduled (un)publish timestamps (admin-set; nullable)
  publishAt: timestamp('publish_at', { mode: 'string', withTimezone: true }),
  unpublishAt: timestamp('unpublish_at', { mode: 'string', withTimezone: true }),
  version: integer('version').default(1),

  // Funding
  payoutAmount: numeric('payout_amount').default('10000'), // In cents ($100 default)

  // Display picture URL
  imageUrl: varchar('image_url'),

  // Image location (where the display photo was taken)
  imageLocationLat: doublePrecision('image_location_lat'),
  imageLocationLng: doublePrecision('image_location_lng'),

  // Street description (e.g. "S Pulaski Rd between W Roosevelt Rd and W 16th St")
  description: varchar('description', { length: 500 }),

  // Admin who created this route
  createdById: integer('created_by_id'),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Route scan session table (mobile Scout & Clean flow)
export const routeScans = pgTable('route_scans', {
  id: serial('id').primaryKey(),
  routeId: integer('route_id').notNull(),
  // Block linkage, resolved at scan creation via blocks.route_id (1:1 — each
  // block owns one route). Nullable: legacy scans predate the column, and a
  // route without a block row yields null. Enables exclusion-zone checks on
  // route-scan observations (migration 0044).
  blockId: integer('block_id').references(() => blocks.id),
  workerId: integer('worker_id').notNull(),
  deviceType: varchar('device_type', { length: 10 }).default('gps').notNull(),
  phase: varchar('phase', { length: 30 }).default('scouting').notNull(),
  coveragePercent: doublePrecision('coverage_percent').default(0).notNull(),
  scoutStartedAt: timestamp('scout_started_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  scoutCompletedAt: timestamp('scout_completed_at', { mode: 'string', withTimezone: true }),
  cleanStartedAt: timestamp('clean_started_at', { mode: 'string', withTimezone: true }),
  cleanCompletedAt: timestamp('clean_completed_at', { mode: 'string', withTimezone: true }),
  startLat: doublePrecision('start_lat').notNull(),
  startLng: doublePrecision('start_lng').notNull(),
  scoutPathGeoJson: text('scout_path_geojson'),
  cleanPathGeoJson: text('clean_path_geojson'),
  totalTrashVolumeLiters: doublePrecision('total_trash_volume_liters'),
  totalTrashWeightLbs: doublePrecision('total_trash_weight_lbs'),
  pointsAwarded: integer('points_awarded').default(0).notNull(),
  status: varchar('status', { length: 30 }).default('in_progress').notNull(),

  // Admin proxy execution audit
  performedByAdminId: integer('performed_by_admin_id').references(() => workers.id),

  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),

  // Server-verified completion (migration 0050, lib/scan-verification.ts).
  // Stamped at scouting → scout_review only when the server-computed route
  // coverage reached LAP_ONE_MIN_ROUTE_COVERAGE (and the client proof agreed);
  // a scan without it cannot be completed.
  lapOneVerifiedAt: timestamp('lap_one_verified_at', { mode: 'string', withTimezone: true }),
  // Route coverage the SERVER computed from this scan's coverage events when
  // it completed (0..1). Null on scans completed before verification existed;
  // those can never complete a job.
  verifiedCoverageRatio: doublePrecision('verified_coverage_ratio'),
  // Route coverage the SERVER computed from this scan's attributed coverage
  // events at scouting → scout_review, when lap one was stamped (0..1).
  lapOneCoverageRatio: doublePrecision('lap_one_coverage_ratio'),

  // GPS plausibility (migration 0053, lib/gps-plausibility.ts). POST
  // /api/scans/coverage drops implausible fixes before recording them and
  // counts kept/dropped fixes here for the scan they would have counted
  // toward. `gpsDropReasons` is {reason: count}; `gpsSuspect` is set once the
  // dropped share is high enough for review to look (GPS_SUSPECT_*).
  gpsPointsKept: integer('gps_points_kept').default(0).notNull(),
  gpsPointsDropped: integer('gps_points_dropped').default(0).notNull(),
  gpsDropReasons: jsonb('gps_drop_reasons').$type<Record<string, number>>().default({}).notNull(),
  gpsSuspect: boolean('gps_suspect').default(false).notNull(),
})

// Hotspots recorded during route scan sessions
export const scanHotspots = pgTable('scan_hotspots', {
  id: serial('id').primaryKey(),
  scanId: integer('scan_id').notNull(),
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  trashTypes: varchar('trash_types', { length: 500 }).default('[]').notNull(),
  severity: integer('severity').default(1).notNull(),
  // Server-capped (lib/hotspot-checks.ts MAX_HOTSPOT_VOLUME_LITERS) — the
  // value scan points use. The client's own figure is kept beside it.
  estimatedVolumeLiters: doublePrecision('estimated_volume_liters'),
  clientEstimatedVolumeLiters: doublePrecision('client_estimated_volume_liters'),
  estimatedWeightLbs: doublePrecision('estimated_weight_lbs'),
  confidence: doublePrecision('confidence').default(0).notNull(),
  beforePhotoUrl: varchar('before_photo_url', { length: 500 }).notNull(),
  afterPhotoUrl: varchar('after_photo_url', { length: 500 }),
  arAnchorData: text('ar_anchor_data'),
  depthData: text('depth_data'),
  detectedAt: timestamp('detected_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  cleanedAt: timestamp('cleaned_at', { mode: 'string', withTimezone: true }),
  // The CLIENT's own before/after match score (0..1) — a claim, stored for
  // review only. Scan points never read it (lib/hotspot-checks.ts).
  verificationScore: doublePrecision('verification_score'),
  status: varchar('status', { length: 20 }).default('detected').notNull(),
})

// One row per evidence photo URL (migration 0053, lib/photo-fingerprint.ts):
// sha256 + 64-bit dHash of the stored image, EXIF capture time from the
// original upload, and the first job/scan the photo was submitted for. Used
// to flag identical before/after, near-duplicates and stale EXIF, and to
// refuse exact reuse of one photo across different jobs or scans.
export const photoFingerprints = pgTable('photo_fingerprints', {
  id: serial('id').primaryKey(),
  url: varchar('url', { length: 500 }).notNull(),
  status: varchar('status', { length: 16 }).default('ok').notNull(), // 'ok' | 'unverified'
  sha256: char('sha256', { length: 64 }),
  dhash: bigint('dhash', { mode: 'bigint' }),
  byteSize: integer('byte_size'),
  width: integer('width'),
  height: integer('height'),
  exifCapturedAt: timestamp('exif_captured_at', { mode: 'string', withTimezone: true }),
  uploadedByWorkerId: integer('uploaded_by_worker_id'),
  firstJobId: integer('first_job_id').references(() => jobs.id, { onDelete: 'set null' }),
  firstScanId: integer('first_scan_id').references(() => routeScans.id, { onDelete: 'set null' }),
  flags: jsonb('flags').$type<string[]>().default([]).notNull(),
  error: varchar('error', { length: 200 }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  urlUnique: uniqueIndex('photo_fingerprints_url_unique').on(table.url),
  sha256Idx: index('photo_fingerprints_sha256_idx').on(table.sha256),
  createdAtIdx: index('photo_fingerprints_created_at_idx').on(table.createdAt),
}))

export type PhotoFingerprintRow = typeof photoFingerprints.$inferSelect

// First job each photo was submitted for (migration 0053). Keys are
// `url:<url>` and `sha256:<hex>`; the primary key makes the first binding
// atomic, so two jobs can never both claim the same photo
// (lib/photo-fingerprint-db.ts claimPhotoForJob).
export const photoJobBindings = pgTable('photo_job_bindings', {
  photoKey: text('photo_key').primaryKey(),
  jobId: integer('job_id').notNull().references(() => jobs.id, { onDelete: 'cascade' }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// AI evidence review per job (migration 0053). Written by the Python review
// worker (upsert on job_id); the site only reads it for the admin review
// queue, where a missing row reads as "not reviewed yet".
export const jobAiReviews = pgTable('job_ai_reviews', {
  jobId: integer('job_id').primaryKey().references(() => jobs.id, { onDelete: 'cascade' }),
  status: text('status').notNull(), // 'ok' | 'flagged' | 'error' (CHECK in 0053)
  verdict: jsonb('verdict').notNull(),
  model: text('model').notNull(),
  inputHash: text('input_hash'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }),
})

export type JobAiReviewRow = typeof jobAiReviews.$inferSelect

// Material donation inquiry status enum
export const materialDonationStatusEnum = pgEnum('enum_material_donations_status', [
  'pending',      // New inquiry, needs follow-up
  'contacted',    // Admin has reached out
  'scheduled',    // Pickup/dropoff scheduled
  'received',     // Donation received
  'cancelled',    // Cancelled by donor or admin
])

// Material donations table (for tracking supply/equipment donations)
export const materialDonations = pgTable('material_donations', {
  id: serial('id').primaryKey(),

  // Donor info
  name: varchar('name', { length: 100 }).notNull(),
  email: varchar('email', { length: 255 }).notNull(),
  phone: varchar('phone', { length: 20 }),
  company: varchar('company', { length: 100 }),

  // Donation details
  items: text('items').notNull(), // Description of items being donated
  itemCategories: jsonb('item_categories'), // Array of categories: gloves, brooms, rakes, etc.
  quantity: varchar('quantity', { length: 100 }), // Approximate quantity/amount
  condition: varchar('condition', { length: 50 }), // new, used-good, used-fair

  // Logistics
  preferredMethod: varchar('preferred_method', { length: 20 }), // 'pickup' or 'dropoff'
  address: text('address'), // For pickup
  availableDates: text('available_dates'), // When donor is available
  notes: text('notes'), // Additional notes from donor

  // Admin tracking
  status: materialDonationStatusEnum('status').default('pending').notNull(),
  adminNotes: text('admin_notes'),
  scheduledDate: timestamp('scheduled_date', { mode: 'string', withTimezone: true }),
  receivedDate: timestamp('received_date', { mode: 'string', withTimezone: true }),
  estimatedValue: numeric('estimated_value'), // For tax receipt purposes

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Type exports
export type Worker = typeof workers.$inferSelect
export type NewWorker = typeof workers.$inferInsert
export type WaitlistEntry = typeof waitlistEntries.$inferSelect
export type NewWaitlistEntry = typeof waitlistEntries.$inferInsert
export type Media = typeof media.$inferSelect
export type NewMedia = typeof media.$inferInsert
export type Sponsor = typeof sponsors.$inferSelect
export type NewSponsor = typeof sponsors.$inferInsert
export type Block = typeof blocks.$inferSelect
export type NewBlock = typeof blocks.$inferInsert
export type Job = typeof jobs.$inferSelect
export type NewJob = typeof jobs.$inferInsert
export type Transaction = typeof transactions.$inferSelect
export type NewTransaction = typeof transactions.$inferInsert
export type BountyRow = typeof bounties.$inferSelect
export type NewBountyRow = typeof bounties.$inferInsert
export type SponsorPackage = typeof sponsorPackages.$inferSelect
export type NewSponsorPackage = typeof sponsorPackages.$inferInsert
export type SponsorDeliverablePackage = typeof sponsorDeliverablePackages.$inferSelect
export type NewSponsorDeliverablePackage = typeof sponsorDeliverablePackages.$inferInsert
export type SponsorDeliverableItem = typeof sponsorDeliverableItems.$inferSelect
export type NewSponsorDeliverableItem = typeof sponsorDeliverableItems.$inferInsert
export type EmailUnsubscribe = typeof emailUnsubscribes.$inferSelect
export type NewEmailUnsubscribe = typeof emailUnsubscribes.$inferInsert
export type NewsletterSend = typeof newsletterSends.$inferSelect
export type NewNewsletterSend = typeof newsletterSends.$inferInsert
export type FeedbackTicket = typeof feedbackTickets.$inferSelect
export type NewFeedbackTicket = typeof feedbackTickets.$inferInsert
export type FeedbackReply = typeof feedbackReplies.$inferSelect
export type NewFeedbackReply = typeof feedbackReplies.$inferInsert
export type ForumPost = typeof forumPosts.$inferSelect
export type NewForumPost = typeof forumPosts.$inferInsert
export type ForumReply = typeof forumReplies.$inferSelect
export type NewForumReply = typeof forumReplies.$inferInsert
export type ForumLike = typeof forumLikes.$inferSelect
export type NewForumLike = typeof forumLikes.$inferInsert
export type ServiceArea = typeof serviceAreas.$inferSelect
export type NewServiceArea = typeof serviceAreas.$inferInsert
export type Route = typeof routes.$inferSelect
export type NewRoute = typeof routes.$inferInsert
export type RouteScanRow = typeof routeScans.$inferSelect
export type NewRouteScanRow = typeof routeScans.$inferInsert
export type ScanHotspotRow = typeof scanHotspots.$inferSelect
export type NewScanHotspotRow = typeof scanHotspots.$inferInsert

// World observation model — the unified map/digital-twin layer fed by both
// route-scan hotspots and spot reports. See docs/superpowers/specs/2026-07-01-
// unified-scan-ecosystem-and-immersive-route-design.md §2.
//
// Field shape is split deliberately: `itemType`/`state`/`confidence`/media/
// capture-provenance fields stay as real columns because they're universal
// across every domain (litter, vegetation, construction, land_use, and
// whatever's added later) AND `itemType` specifically is queried directly in
// the supersession logic (recordWorldObservation), so it needs to stay a real
// indexable column, not buried in JSON. `attributes` holds whatever numeric/
// descriptive payload is specific to a given domain — litter's severity and
// volume/weight estimates today, a future vegetation domain's health grade,
// a future construction domain's progress percent — without requiring a
// schema migration (or a new named column) every time a new domain is added.
export const worldObservations = pgTable('world_observations', {
  id: serial('id').primaryKey(),
  scanHotspotId: integer('scan_hotspot_id'),
  communityReportId: integer('community_report_id'),
  sourceType: varchar('source_type', { length: 20 }).notNull(), // 'route_scan' | 'spot_report' | 'ar_auto_detect'
  domain: varchar('domain', { length: 20 }).default('litter').notNull(), // 'litter' | 'vegetation' | 'construction' | 'land_use'
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  itemType: varchar('item_type', { length: 50 }), // null when state='clean' (nothing dirty found)
  state: varchar('state', { length: 10 }).notNull(), // 'dirty' | 'clean'
  // Litter-truth lifecycle (Pillar 8 Task 4): 'observed' (identified from
  // afar — distant detection photo + position/type, no close capture) or
  // 'diagnosed' (a close-range capture — weight+width from a close confirm,
  // or Object Capture — upgraded it). Computed in recordWorldObservation
  // from the POST-MERGE attributes (see that function's comment for why),
  // so a later close-confirm on the same cell+itemType upgrades the row.
  // MERGE ACTION: migration 0029 adds this column — see that file's header.
  diagnosticStatus: varchar('diagnostic_status', { length: 10 }).default('observed').notNull(),
  attributes: jsonb('attributes'), // domain-specific payload, e.g. litter: {severity, estimatedWeightGrams, estimatedWidthMeters, yawDegrees}
  confidence: doublePrecision('confidence'),
  mediaUrl: varchar('media_url', { length: 500 }),
  depthData: text('depth_data'),
  arAnchorData: text('ar_anchor_data'),
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }).notNull(),
  isCurrent: boolean('is_current').default(true).notNull(),
  excludedPrivateProperty: boolean('excluded_private_property').default(false).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  // The worker whose report this is (migration 0053). Rectification refuses
  // its bonus when the rectifier reported the dirty observation themselves.
  // Null on legacy rows and system writers; see lib/rectify-checks.ts for the
  // legacy fallback through scan_hotspots / community_reports.
  reporterWorkerId: integer('reporter_worker_id'),
}, (table) => ({
  // Bbox reads always filter on is_current first, then lat/lng (8 map/API
  // routes); cell-keyed reads (supersession, coverage boosts) hit
  // location_cell_id. Applied to prod by migration 0044.
  currentLatLngIdx: index('world_observations_current_lat_lng_idx').on(table.isCurrent, table.lat, table.lng),
  cellIdx: index('world_observations_cell_idx').on(table.locationCellId),
}))

export type WorldObservationRow = typeof worldObservations.$inferSelect
export type NewWorldObservationRow = typeof worldObservations.$inferInsert

export const scanCoverageEvents = pgTable('scan_coverage_events', {
  id: serial('id').primaryKey(),
  deviceId: varchar('device_id', { length: 64 }).notNull(),
  platform: varchar('platform', { length: 16 }).notNull(), // 'ios' | 'android'
  workerId: integer('worker_id'),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  distanceMeters: doublePrecision('distance_meters').notNull(),
  scanMode: varchar('scan_mode', { length: 10 }).notNull(), // 'route' | 'spot'
  deviceTier: varchar('device_tier', { length: 16 }).notNull(), // 'basic' | 'scanner' | 'pro_scanner'
  quality: varchar('quality', { length: 8 }).default('grazed').notNull(), // 'seen' | 'grazed' | 'close'
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }).notNull(),
  // R2 object key for the raw capture blob this event corresponds to (see
  // rawScanKey() in site/src/lib/r2.ts) — null until the device confirms the
  // presigned upload succeeded. Nothing else derives this key server-side
  // because the upload extension isn't otherwise recorded.
  rawR2Key: varchar('raw_r2_key', { length: 500 }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  // The route scan this event was walked under (migration 0050). Stamped by
  // the SERVER at ingest (POST /api/scans/coverage) from the caller's single
  // active route scan — never taken from the client. Null for spot-mode
  // events, events with no active scan, and every event before 0050. Scan
  // verification (lib/scan-completion.ts) counts only events carrying it.
  routeScanId: integer('route_scan_id').references(() => routeScans.id),
  // `${workerId}|${deviceId}|${capturedAt ISO}` (migration 0053). Unique, so a
  // retried coverage batch inserts nothing twice and pays nothing twice
  // (lib/scan-coverage.ts recordCoverageEvent). Null on rows before 0053.
  dedupeKey: text('dedupe_key'),
}, (table) => ({
  cellCapturedIdx: index('scan_coverage_events_cell_captured_idx').on(table.locationCellId, table.capturedAt),
  // Scan verification reads "this scan's route events" (lib/scan-completion.ts).
  routeScanIdx: index('scan_coverage_events_route_scan_id_idx').on(table.routeScanId),
  // 0053: the per-device trajectory anchor (gps-plausibility-db.ts) and the
  // rectify presence reads (rectify-checks-db.ts).
  workerDeviceCapturedIdx: index('scan_coverage_events_worker_device_captured_idx').on(table.workerId, table.deviceId, table.capturedAt),
  workerCapturedIdx: index('scan_coverage_events_worker_captured_idx').on(table.workerId, table.capturedAt),
  dedupeKeyUnique: uniqueIndex('scan_coverage_events_dedupe_key_unique').on(table.dedupeKey),
}))

export type ScanCoverageEventRow = typeof scanCoverageEvents.$inferSelect
export type NewScanCoverageEventRow = typeof scanCoverageEvents.$inferInsert

export const cellCoverageSummary = pgTable('cell_coverage_summary', {
  locationCellId: varchar('location_cell_id', { length: 32 }).primaryKey(),
  // Denormalized cell-center coordinates (cellCenter(locationCellId)) so map
  // reads can bbox-filter in SQL instead of fetching every row and filtering
  // in JS (migration 0044). Nullable: legacy rows predate the columns and are
  // backfilled by 0044 / repaired on their next upsert; readers keep the JS
  // cellCenter() filter as the fallback for any row still null.
  lat: doublePrecision('lat'),
  lng: doublePrecision('lng'),
  firstScannedAt: timestamp('first_scanned_at', { mode: 'string', withTimezone: true }).notNull(),
  lastScannedAt: timestamp('last_scanned_at', { mode: 'string', withTimezone: true }).notNull(),
  totalDistanceMeters: doublePrecision('total_distance_meters').default(0).notNull(),
  scanCount: integer('scan_count').default(0).notNull(),
  bestQuality: varchar('best_quality', { length: 8 }).default('grazed').notNull(), // 'seen' | 'grazed' | 'close'
  lastCloseScannedAt: timestamp('last_close_scanned_at', { mode: 'string', withTimezone: true }),
})

export type CellCoverageSummaryRow = typeof cellCoverageSummary.$inferSelect
export type NewCellCoverageSummaryRow = typeof cellCoverageSummary.$inferInsert

// Processed 3D reconstruction outputs (Phase 2/3 of the scan-guidance/world-model
// design, docs/superpowers/specs/2026-07-02-scan-guidance-world-model-and-rewards-
// design.md §2.4). Files themselves live in R2 as open formats (.ply/.glb) at
// `reconstructed/{locationCellId}/{version}/{assetType}.{format}` — this table is
// the explicit-provenance link back to the scan-coverage events that produced them.
export const reconstructionAssets = pgTable('reconstruction_assets', {
  id: serial('id').primaryKey(),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  assetType: varchar('asset_type', { length: 20 }).notNull(), // 'pointcloud' | 'splat' | 'mesh'
  format: varchar('format', { length: 10 }).notNull(), // 'ply' | 'glb' | 'gltf' | 'laz'
  r2Key: varchar('r2_key', { length: 500 }).notNull(),
  version: integer('version').notNull(),
  sourceScanCoverageEventIds: jsonb('source_scan_coverage_event_ids').notNull(), // number[]
  activatedAt: timestamp('activated_at', { mode: 'string', withTimezone: true }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  cellVersionIdx: index('reconstruction_assets_cell_version_idx').on(table.locationCellId, table.version),
}))

export type ReconstructionAssetRow = typeof reconstructionAssets.$inferSelect
export type NewReconstructionAssetRow = typeof reconstructionAssets.$inferInsert

// Index of capture sessions whose raw blobs (video/depth/pose/USDZ/frames/
// manifest) live in R2 (`gcc-scan-storage`). Before this, uploaded scans were
// orphaned — discoverable only by prefix-scanning R2. The Kotlin backend
// (`backend/`, the scan control-plane) parses each R2 manifest (the shared
// `EnvScanManifest`) and mirrors it here so sessions are queryable and linkable
// to coverage/reconstruction. Applied by migration 0033.
export const scanSessions = pgTable('scan_sessions', {
  id: serial('id').primaryKey(),
  sessionId: varchar('session_id', { length: 64 }).notNull(),
  deviceId: varchar('device_id', { length: 64 }).notNull(),
  deviceModel: varchar('device_model', { length: 64 }),
  platform: varchar('platform', { length: 16 }), // 'ios' | 'android' | null
  osVersion: varchar('os_version', { length: 32 }),
  lapKind: varchar('lap_kind', { length: 16 }), // 'baseline' | 'clean' | null
  routeId: integer('route_id'),
  locationCellId: varchar('location_cell_id', { length: 32 }), // anchor cell (parsed from the manifest R2 key)
  startedAt: timestamp('started_at', { mode: 'string', withTimezone: true }),
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
  photoCount: integer('photo_count').default(0).notNull(),
  chunkCount: integer('chunk_count').default(0).notNull(),
  observedItemCount: integer('observed_item_count').default(0).notNull(),
  scannedCellCount: integer('scanned_cell_count').default(0).notNull(),
  hasObjectCapture: boolean('has_object_capture').default(false).notNull(),
  objectCaptureR2Key: varchar('object_capture_r2_key', { length: 500 }),
  manifestR2Key: varchar('manifest_r2_key', { length: 500 }).notNull(),
  totalBytes: bigint('total_bytes', { mode: 'number' }), // sum of indexed artifact sizes (est)
  reconstructionVersion: integer('reconstruction_version'), // set once a reconstruction exists (Phase 3)
  reconstructedAt: timestamp('reconstructed_at', { mode: 'string', withTimezone: true }),
  // Session -> world-splat registration (docs/governance/scan-splat-registration-contract.md).
  // Count set at index time by the Ktor indexer; the rest by the DGX worker's solve.
  geoRefEventCount: integer('geo_ref_event_count').default(0).notNull(),
  registrationStatus: varchar('registration_status', { length: 16 }), // 'world' | 'local-only' | null (unsolved)
  registrationResidualM: real('registration_residual_m'),
  worldRegistration: jsonb('world_registration'), // solve record: clock offset, segments, anchor, method
  // Flight recorder (scan-session-replay PRD Phase 1, migration 0037): the
  // session's gzipped JSONL event stream in R2, plus headline metrics the
  // Ktor indexer extracts from it at index time. All nullable — null means
  // "session predates the flight recorder or the log isn't uploaded yet",
  // never zero activity.
  eventLogR2Key: varchar('event_log_r2_key', { length: 500 }),
  eventCount: integer('event_count'),
  gateFlipCount: integer('gate_flip_count'),
  tapCount: integer('tap_count'),
  confirmCount: integer('confirm_count'),
  rejectCount: integer('reject_count'),
  raycastMissCount: integer('raycast_miss_count'),
  detectionCount: integer('detection_count'),
  uploadAttemptCount: integer('upload_attempt_count'),
  uploadFailureCount: integer('upload_failure_count'),
  errorCount: integer('error_count'),
  // Capture health (splat-capture reliability spec, migration 0039): headline
  // quality summary the Ktor indexer extracts from the manifest's
  // `captureHealth` block. Nullable — null means "session predates capture
  // health or the summary failed to decode", never zero degradation.
  videoCoveragePct: real('video_coverage_pct'), // videoCoveredSeconds / sessionSeconds * 100, clamped 0..100
  maxThermalState: varchar('max_thermal_state', { length: 16 }), // nominal|fair|serious|critical
  droppedVideoChunks: integer('dropped_video_chunks'),
  // Worker whose upload grants cover this session's manifest (capture_upload_
  // grants, migration 0054). Null for sessions uploaded before grants existed.
  workerId: integer('worker_id'),
  indexedAt: timestamp('indexed_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  sessionIdKey: uniqueIndex('scan_sessions_session_id_key').on(table.sessionId),
  cellCompletedIdx: index('scan_sessions_cell_completed_idx').on(table.locationCellId, table.completedAt),
  // Partial index `scan_sessions_unreconstructed_idx` (WHERE reconstruction_version IS NULL)
  // is applied in migration 0033 for the "needs reconstructing" queue lookup.
}))

export type ScanSessionRow = typeof scanSessions.$inferSelect
export type NewScanSessionRow = typeof scanSessions.$inferInsert

// Per-artifact index of the individual R2 objects that make up each session.
// Idempotent on `r2_key` so re-indexing updates rather than duplicates.
export const captureChunks = pgTable('capture_chunks', {
  id: serial('id').primaryKey(),
  sessionId: varchar('session_id', { length: 64 }).notNull(),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  artifactType: varchar('artifact_type', { length: 24 }).notNull(), // video|depth|pose|frame_color|frame_depth|usdz|manifest|photo|other
  r2Key: varchar('r2_key', { length: 500 }).notNull(),
  format: varchar('format', { length: 16 }), // mp4|mov|png|jpg|jsonl|usdz|json|bin|ply
  byteSize: bigint('byte_size', { mode: 'number' }),
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }),
  indexedAt: timestamp('indexed_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  r2KeyKey: uniqueIndex('capture_chunks_r2_key_key').on(table.r2Key),
  sessionIdx: index('capture_chunks_session_idx').on(table.sessionId),
  cellIdx: index('capture_chunks_cell_idx').on(table.locationCellId),
}))

export type CaptureChunkRow = typeof captureChunks.$inferSelect
export type NewCaptureChunkRow = typeof captureChunks.$inferInsert

// Durable Phase-3 reconstruction queue (docs/superpowers/specs/2026-07-10-
// phase3-reconstruction-queue-and-prototype-design.md §4). The Kotlin backend
// enqueues one job per completed, unreconstructed, chunk-bearing scan session
// after each index sweep; the DGX GPU worker (scripts/reconstruction/
// recon_worker.py) leases with SELECT … FOR UPDATE SKIP LOCKED and, on
// success, closes every job for the same cell (job unit = session, work unit
// = cell). Never consumed from Vercel/neon-http — leasing needs a real
// transaction. Applied by migration 0034.
export const reconstructionJobs = pgTable('reconstruction_jobs', {
  id: serial('id').primaryKey(),
  sessionId: varchar('session_id', { length: 64 }).notNull(),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  status: varchar('status', { length: 16 }).default('queued').notNull(), // 'queued' | 'leased' | 'done' | 'failed'
  attempts: integer('attempts').default(0).notNull(),
  leasedUntil: timestamp('leased_until', { mode: 'string', withTimezone: true }),
  lastError: text('last_error'),
  // Set by Task 7 (DGX reconstruction worker) when a job is skipped on
  // capture-health grounds (e.g. 'thermal', 'low_coverage'). Migration 0039.
  splatSkipReason: varchar('splat_skip_reason', { length: 32 }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
}, (table) => ({
  sessionIdKey: uniqueIndex('reconstruction_jobs_session_id_key').on(table.sessionId),
  claimIdx: index('reconstruction_jobs_claim_idx').on(table.status, table.createdAt),
  cellIdx: index('reconstruction_jobs_cell_idx').on(table.locationCellId),
}))

// Manifests the scan indexer (lib/scan-index) can never accept as uploaded:
// undecodable, missing a required field, a key that doesn't match the ids it
// claims, too large, or a session owned by another device. Lets the indexer
// skip them until they are re-uploaded, and lets the watchdog alert on each one
// once rather than staying red. Applied by migration 0052.
export const scanManifestRejects = pgTable('scan_manifest_rejects', {
  // text, not varchar: the key is whatever was uploaded, and an over-long one
  // is exactly what gets rejected — its insert must not fail.
  r2Key: text('r2_key').primaryKey(),
  reason: text('reason').notNull(),
  size: bigint('size', { mode: 'number' }).notNull(), // listed size of the rejected upload
  lastModified: timestamp('last_modified', { mode: 'string', withTimezone: true }).notNull(),
  firstSeenAt: timestamp('first_seen_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  alertedAt: timestamp('alerted_at', { mode: 'string', withTimezone: true }),
})

// Every raw/ capture key a presigned upload URL was issued for, and to whom
// (/api/captures/upload-url, /api/scans/[id]/upload-url). The scan indexer
// accepts a post-cutover manifest only if its key — and every key it names —
// was granted to one worker (lib/scan-index/grants.ts). Applied by migration 0054.
export const captureUploadGrants = pgTable('capture_upload_grants', {
  r2Key: text('r2_key').primaryKey(),
  workerId: integer('worker_id').notNull(),
  deviceId: varchar('device_id', { length: 128 }),
  sessionId: varchar('session_id', { length: 128 }),
  issuedAt: timestamp('issued_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  lastIssuedAt: timestamp('last_issued_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  workerIdx: index('capture_upload_grants_worker_idx').on(table.workerId),
  issuedIdx: index('capture_upload_grants_issued_idx').on(table.issuedAt),
}))

// The first worker granted an upload under a session folder owns that session:
// upload-url refuses every other worker a key in it, even a new key name
// (lib/scan-index/grants.ts). Device folders are deliberately not bound — test
// handsets are shared between accounts. Applied by migration 0054.
export const captureNamespaceOwners = pgTable('capture_namespace_owners', {
  // The folder segment itself (raw/{cell}/{ns_id}/…), whatever request shape
  // names it — only session-shaped requests ever create a row.
  nsId: varchar('ns_id', { length: 64 }).primaryKey(),
  workerId: integer('worker_id').notNull(),
  firstGrantedAt: timestamp('first_granted_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type ReconstructionJobRow = typeof reconstructionJobs.$inferSelect
export type NewReconstructionJobRow = typeof reconstructionJobs.$inferInsert

// Chicago Data Portal 311 mirror + GCC visual evidence (docs/superpowers/
// specs/2026-07-10-chicago-311-civic-loop-design.md). The Ktor backend's
// CivicReportSync upserts GCC-relevant SR types from the city's SODA API
// (keyed by the city's own sr_number, cell id computed at ingest with the
// shared gridCell10m); the app prompts on Open reports near an active scan
// and posts evidence links here. Applied by migration 0035.
export const civicReports = pgTable('civic_reports', {
  id: serial('id').primaryKey(),
  srNumber: varchar('sr_number', { length: 50 }).notNull(),
  srType: varchar('sr_type', { length: 100 }).notNull(),
  status: varchar('status', { length: 50 }).notNull(), // portal values: 'Open' | 'Completed' | 'Canceled' | …
  ownerDepartment: varchar('owner_department', { length: 100 }),
  createdDate: timestamp('created_date', { mode: 'string', withTimezone: true }),
  lastModifiedDate: timestamp('last_modified_date', { mode: 'string', withTimezone: true }),
  closedDate: timestamp('closed_date', { mode: 'string', withTimezone: true }),
  streetAddress: varchar('street_address', { length: 200 }),
  communityArea: integer('community_area'),
  lat: doublePrecision('lat'),
  lng: doublePrecision('lng'),
  locationCellId: varchar('location_cell_id', { length: 32 }),
  firstCapturedAt: timestamp('first_captured_at', { mode: 'string', withTimezone: true }),
  syncedAt: timestamp('synced_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  srNumberKey: uniqueIndex('civic_reports_sr_number_key').on(table.srNumber),
  cellIdx: index('civic_reports_cell_idx').on(table.locationCellId),
  statusTypeIdx: index('civic_reports_status_type_idx').on(table.status, table.srType),
  communityAreaIdx: index('civic_reports_community_area_idx').on(table.communityArea),
}))

export const civicReportEvidence = pgTable('civic_report_evidence', {
  id: serial('id').primaryKey(),
  srNumber: varchar('sr_number', { length: 50 }).notNull(),
  sessionId: varchar('session_id', { length: 64 }).notNull(),
  observationId: integer('observation_id'),
  photoR2Key: varchar('photo_r2_key', { length: 500 }),
  workerId: integer('worker_id'),
  matchConfidence: varchar('match_confidence', { length: 8 }).default('user').notNull(), // 'user' | 'auto'
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  srSessionKey: uniqueIndex('civic_report_evidence_sr_session_key').on(table.srNumber, table.sessionId),
  srIdx: index('civic_report_evidence_sr_idx').on(table.srNumber),
}))

export type CivicReportRow = typeof civicReports.$inferSelect
export type NewCivicReportRow = typeof civicReports.$inferInsert
export type CivicReportEvidenceRow = typeof civicReportEvidence.$inferSelect
export type NewCivicReportEvidenceRow = typeof civicReportEvidence.$inferInsert

export type CommunityReport = typeof communityReports.$inferSelect
export type NewCommunityReport = typeof communityReports.$inferInsert
export type CommunityReportThanks = typeof communityReportThanks.$inferSelect
export type NewCommunityReportThanks = typeof communityReportThanks.$inferInsert
export type MaterialDonation = typeof materialDonations.$inferSelect
export type NewMaterialDonation = typeof materialDonations.$inferInsert

// In-kind donation acknowledgment records (received gifts, not intake
// inquiries — materialDonations above is the intake form). Mirrors migration
// 0013_add_in_kind_donations.sql exactly; consumed by the /in-kind
// certificate/letter/internal-record pages via src/lib/in-kind-loader.ts.
export const inKindDonorTypeEnum = pgEnum('enum_in_kind_donations_donor_type', [
  'organization',
  'business',
  'individual',
  'government',
])

export const inKindStatusEnum = pgEnum('enum_in_kind_donations_status', [
  'received',
  'acknowledged',
  'archived',
])

export const inKindAckMethodEnum = pgEnum('enum_in_kind_donations_ack_method', [
  'email',
  'mail',
  'in_person',
])

export const inKindValueSourceEnum = pgEnum('enum_in_kind_donations_value_source', [
  'donor_provided',
  'staff_estimate',
  'appraisal',
  'unknown',
])

export const inKindDonations = pgTable('in_kind_donations', {
  id: serial('id').primaryKey(),
  slug: varchar('slug', { length: 100 }).notNull().unique(),
  isTemplate: boolean('is_template').default(false).notNull(),

  donorName: varchar('donor_name', { length: 200 }).notNull(),
  donorType: inKindDonorTypeEnum('donor_type').default('individual').notNull(),
  donorContactName: varchar('donor_contact_name', { length: 100 }),
  donorEmail: varchar('donor_email', { length: 255 }),
  donorPhone: varchar('donor_phone', { length: 30 }),
  donorAddressStreet: varchar('donor_address_street', { length: 200 }),
  donorAddressCity: varchar('donor_address_city', { length: 100 }),
  donorAddressState: varchar('donor_address_state', { length: 50 }),
  donorAddressZip: varchar('donor_address_zip', { length: 20 }),

  donationDate: varchar('donation_date', { length: 20 }).notNull(),
  receivedDate: varchar('received_date', { length: 20 }),
  dropOffLocation: varchar('drop_off_location', { length: 200 }),

  estimatedValueCents: integer('estimated_value_cents'),
  valueSource: inKindValueSourceEnum('value_source').default('unknown').notNull(),
  valueNotes: text('value_notes'),

  storageLocation: varchar('storage_location', { length: 200 }),
  intendedUse: text('intended_use'),
  programFund: varchar('program_fund', { length: 100 }),

  status: inKindStatusEnum('status').default('received').notNull(),
  acknowledgedAt: timestamp('acknowledged_at', { mode: 'string', withTimezone: true }),
  acknowledgmentMethod: inKindAckMethodEnum('acknowledgment_method'),
  signingOfficerName: varchar('signing_officer_name', { length: 100 }),
  signingOfficerTitle: varchar('signing_officer_title', { length: 100 }),
  thankYouMessage: text('thank_you_message'),

  notes: text('notes'),
  createdByWorkerId: integer('created_by_worker_id'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  statusIdx: index('in_kind_donations_status_idx').on(table.status),
  donationDateIdx: index('in_kind_donations_donation_date_idx').on(table.donationDate),
  donorNameIdx: index('in_kind_donations_donor_name_idx').on(table.donorName),
}))

export const inKindDonationItems = pgTable('in_kind_donation_items', {
  id: serial('id').primaryKey(),
  donationId: integer('donation_id')
    .notNull()
    .references(() => inKindDonations.id, { onDelete: 'cascade' }),
  description: varchar('description', { length: 300 }).notNull(),
  quantity: integer('quantity').default(1).notNull(),
  unitLabel: varchar('unit_label', { length: 50 }),
  condition: varchar('condition', { length: 50 }),
  estimatedUnitValueCents: integer('estimated_unit_value_cents'),
  sortOrder: integer('sort_order').default(0).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  donationIdIdx: index('in_kind_donation_items_donation_id_idx').on(table.donationId),
}))

export type InKindDonation = typeof inKindDonations.$inferSelect
export type NewInKindDonation = typeof inKindDonations.$inferInsert
export type InKindDonationItem = typeof inKindDonationItems.$inferSelect
export type NewInKindDonationItem = typeof inKindDonationItems.$inferInsert
export type SpotVerification = typeof spotVerifications.$inferSelect
export type NewSpotVerification = typeof spotVerifications.$inferInsert
export type PointsLedgerEntry = typeof pointsLedger.$inferSelect
export type NewPointsLedgerEntry = typeof pointsLedger.$inferInsert

// =====================
// WALLET, PLEDGES, AND VOLUNTEER CLEANUPS
// =====================
// These tables mirror Exposed declarations in
// server/src/main/kotlin/com/gcc/server/db/tables/ServerOnlyTables.kt.
// They were declared on the Ktor side from day one but the corresponding
// CREATE TABLE statements never made it to prod; backfilled by migration
// 0024 on 2026-05-23 after an audit pass. Amounts stored as integer cents
// to match the existing Ktor service code (the rest of the schema uses
// `numeric` for amounts; this is an intentional divergence).

export const sponsorWallets = pgTable('sponsor_wallets', {
  id: serial('id').primaryKey(),
  sponsorId: integer('sponsor_id'),
  workerId: integer('worker_id'),
  totalBalance: integer('total_balance').default(0).notNull(),
  generalFundBalance: integer('general_fund_balance').default(0).notNull(),
  routeFundBalance: integer('route_fund_balance').default(0).notNull(),
  bountyFundBalance: integer('bounty_fund_balance').default(0).notNull(),
  totalDeposited: integer('total_deposited').default(0).notNull(),
  totalSpent: integer('total_spent').default(0).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  sponsorIdx: index('sponsor_wallets_sponsor_id_idx').on(table.sponsorId),
  workerIdx: index('sponsor_wallets_worker_id_idx').on(table.workerId),
}))

export const walletTransactions = pgTable('wallet_transactions', {
  id: serial('id').primaryKey(),
  walletId: integer('wallet_id').notNull(),
  type: varchar('type', { length: 50 }).notNull(),
  fundType: varchar('fund_type', { length: 50 }).notNull(),
  amount: integer('amount').notNull(),
  balanceAfter: integer('balance_after').notNull(),
  referenceId: integer('reference_id'),
  referenceType: varchar('reference_type', { length: 50 }),
  description: text('description'),
  stripePaymentIntentId: varchar('stripe_payment_intent_id', { length: 255 }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  walletIdx: index('wallet_transactions_wallet_id_idx').on(table.walletId),
}))

// Money leaving the platform to a worker (cash-outs). Historically these were
// admin-initiated ad-hoc payouts; since the wallet-ledger rework they are
// primarily worker-initiated withdrawals from the in-app wallet balance.
// Job payouts are NOT recorded here — their state lives on the jobs row
// (payoutStatus) and, post-rework, on workerWalletEntries. Amounts are
// integer cents, matching the wallet tables above.
export const payouts = pgTable('payouts', {
  id: serial('id').primaryKey(),
  workerId: integer('worker_id').notNull(),
  amountCents: integer('amount_cents').notNull(),
  note: varchar('note'),
  method: payoutMethodEnum('method').notNull(),
  status: payoutStatusEnum('status').notNull(),
  stripeTransferId: varchar('stripe_transfer_id'),
  stripePayoutId: varchar('stripe_payout_id'),
  // Nullable since the wallet rework: worker-initiated withdrawals have no
  // admin. Exactly one of the two initiated-by columns should be set.
  initiatedByAdminId: integer('initiated_by_admin_id'),
  initiatedByWorkerId: integer('initiated_by_worker_id'),
  // Client-supplied key so a retried request can never double-send money.
  idempotencyKey: varchar('idempotency_key').notNull(),
  // Snapshot of where the check goes, frozen at creation (worker edits to
  // their address must not retarget an already-queued check).
  mailingAddress: jsonb('mailing_address'), // { name, street, city, state, zip }
  checkMailedAt: timestamp('check_mailed_at', { mode: 'string', withTimezone: true }),
  checkMailedByAdminId: integer('check_mailed_by_admin_id'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  workerIdx: index('payouts_worker_id_idx').on(table.workerId),
  statusIdx: index('payouts_status_idx').on(table.status),
  idempotencyUnique: uniqueIndex('payouts_idempotency_key_unique').on(table.idempotencyKey),
}))

// Audit ledger behind workers.walletBalanceCents. amountCents is signed:
// positive = credit into the wallet, negative = debit out of it.
//
// kind values:
//   'job_earning'         — credited when an admin approves a job (one per job,
//                           enforced by the partial unique index below)
//   'admin_credit'        — ad-hoc admin credit (bonus / correction)
//   'withdrawal'          — worker cash-out; payoutId links the payouts row
//   'withdrawal_reversal' — refund when a Stripe payout fails after debit
//   'adjustment'          — manual moves (e.g. reassigning a completed job)
//
// The balance column on workers is authoritative for spend checks; this table
// is the trail that explains it. Kept as varchar (not a pg enum) so adding a
// kind later is a code change, not a migration.
export const workerWalletEntries = pgTable('worker_wallet_entries', {
  id: serial('id').primaryKey(),
  workerId: integer('worker_id').notNull(),
  amountCents: integer('amount_cents').notNull(),
  kind: varchar('kind').notNull(),
  jobId: integer('job_id'),
  payoutId: integer('payout_id'),
  note: varchar('note'),
  createdByAdminId: integer('created_by_admin_id'),
  // Client-supplied key for admin credits so a retried request can never
  // double-credit. NULL for server-generated entries (Postgres unique indexes
  // treat NULLs as distinct).
  idempotencyKey: varchar('idempotency_key'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  workerIdx: index('worker_wallet_entries_worker_id_idx').on(table.workerId),
  idempotencyUnique: uniqueIndex('worker_wallet_entries_idempotency_key_unique').on(table.idempotencyKey),
  // A job can only ever produce one primal earning credit — approve-route
  // races collapse onto this index.
  jobEarningUnique: uniqueIndex('worker_wallet_entries_job_earning_unique')
    .on(table.jobId)
    .where(sql`kind = 'job_earning'`),
  // A failed withdrawal can only be refunded once — webhook redeliveries
  // collapse onto this index.
  withdrawalReversalUnique: uniqueIndex('worker_wallet_entries_withdrawal_reversal_unique')
    .on(table.payoutId)
    .where(sql`kind = 'withdrawal_reversal'`),
}))

// Daily aggregate caps on ad-hoc admin wallet credits (lib/admin-credit-caps.ts).
// One row per (scope, subject, UTC day): scope 'admin' counts what an admin
// has credited to anyone today, scope 'target' what a worker has received from
// any admin today. The cap is enforced by Postgres in the upsert itself
// (ON CONFLICT … DO UPDATE … WHERE new_total <= cap), which is the only way
// to make it atomic on neon-http (no interactive transactions). Over-counting
// is the safe failure: a crash after the bump leaves less room, never more.
export const adminCreditDailyTotals = pgTable('admin_credit_daily_totals', {
  scope: varchar('scope', { length: 10 }).notNull(), // 'admin' | 'target'
  subjectId: integer('subject_id').notNull(), // workers.id of the admin or the target
  day: date('day', { mode: 'string' }).notNull(), // UTC calendar date
  totalCents: integer('total_cents').notNull().default(0),
}, (table) => ({
  pk: primaryKey({ columns: [table.scope, table.subjectId, table.day] }),
}))

// Ad-hoc admin credits above the second-approval threshold wait here until a
// DIFFERENT admin approves them (lib/admin-credit-approval.ts), mirroring job
// approval: a conditional UPDATE on status='pending' is the claim, and only an
// approved row produces the admin_credit ledger entry (walletEntryId).
//   status: 'pending' | 'approved' | 'rejected'
export const pendingAdminCredits = pgTable('pending_admin_credits', {
  id: serial('id').primaryKey(),
  targetWorkerId: integer('target_worker_id').notNull(),
  amountCents: integer('amount_cents').notNull(),
  reason: varchar('reason').notNull(),
  // The requesting admin's idempotency key, reused as the ledger entry's key
  // when the credit is approved so a retried request can never double-credit.
  idempotencyKey: varchar('idempotency_key').notNull(),
  createdByAdminId: integer('created_by_admin_id').notNull(),
  status: varchar('status', { length: 20 }).notNull().default('pending'),
  decidedByAdminId: integer('decided_by_admin_id'),
  decidedAt: timestamp('decided_at', { mode: 'string', withTimezone: true }),
  decisionNote: varchar('decision_note'),
  walletEntryId: integer('wallet_entry_id'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  statusIdx: index('pending_admin_credits_status_idx').on(table.status),
  idempotencyUnique: uniqueIndex('pending_admin_credits_idempotency_key_unique').on(table.idempotencyKey),
}))

export const bountyPledges = pgTable('bounty_pledges', {
  id: serial('id').primaryKey(),
  bountyId: integer('bounty_id').notNull(),
  pledgerType: varchar('pledger_type', { length: 50 }).notNull(),
  pledgerId: integer('pledger_id').notNull(),
  walletId: integer('wallet_id').notNull(),
  amount: integer('amount').notNull(),
  status: varchar('status', { length: 50 }).default('active').notNull(),
  renewalCount: integer('renewal_count').default(0).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  bountyIdx: index('bounty_pledges_bounty_id_idx').on(table.bountyId),
  walletIdx: index('bounty_pledges_wallet_id_idx').on(table.walletId),
}))

export const volunteerCleanups = pgTable('volunteer_cleanups', {
  id: serial('id').primaryKey(),
  reportId: integer('report_id').notNull(),
  workerId: integer('worker_id').notNull(),
  beforeMediaUrls: jsonb('before_media_urls').notNull(),
  afterMediaUrls: jsonb('after_media_urls').notNull(),
  trashHeapMediaUrl: varchar('trash_heap_media_url', { length: 2048 }),
  aiTrashAnalysis: jsonb('ai_trash_analysis'),
  locationLat: doublePrecision('location_lat').notNull(),
  locationLng: doublePrecision('location_lng').notNull(),
  locationVerified: boolean('location_verified').default(false).notNull(),
  startedAt: timestamp('started_at', { mode: 'string', withTimezone: true }).notNull(),
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
  durationMinutes: integer('duration_minutes'),
  pdfUrl: varchar('pdf_url', { length: 2048 }),
  status: varchar('status', { length: 50 }).default('in_progress').notNull(),
  verificationNotes: text('verification_notes'),
  pointsAwarded: integer('points_awarded').default(0).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  reportIdx: index('volunteer_cleanups_report_id_idx').on(table.reportId),
  workerIdx: index('volunteer_cleanups_worker_id_idx').on(table.workerId),
}))

// Feed comments — see migrations/0030_add_feed_comments.sql. feedItemId is a
// synthetic composite id like "spot_123" / "bounty_created_45" (the feed
// itself is an in-memory union of several tables, not a materialized one),
// so there's no FK to a feed_items table — just an indexed varchar.
export const feedComments = pgTable('feed_comments', {
  id: serial('id').primaryKey(),
  feedItemId: varchar('feed_item_id', { length: 255 }).notNull(),
  authorId: integer('author_id').notNull(),
  authorName: varchar('author_name', { length: 255 }).notNull(),
  text: text('text').notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  feedItemIdx: index('idx_feed_comments_feed_item').on(table.feedItemId),
  authorIdx: index('idx_feed_comments_author').on(table.authorId),
}))

// Per-feed-item, per-worker profile opt-in CONSENT row: "worker X consents
// to be visible on item Y". Uniqueness is composite (feedItemId, workerId) —
// unique on feedItemId alone would let any worker squat another actor's
// opt-in slot (IDOR). Render contract for the future feed GET: an item's
// actorProfileVisible = a row exists WHERE workerId == the item's actor id —
// never any-row-exists; a stranger's consent row is inert.
export const feedProfileOptIns = pgTable('feed_profile_opt_ins', {
  id: serial('id').primaryKey(),
  feedItemId: varchar('feed_item_id', { length: 255 }).notNull(),
  workerId: integer('worker_id').notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  itemWorkerUnique: uniqueIndex('feed_profile_opt_ins_item_worker_unique').on(table.feedItemId, table.workerId),
  workerIdx: index('idx_feed_profile_opt_ins_worker').on(table.workerId),
}))

// Pillar 9 — block completion reports. Auto-generated when a worker's job
// completion PATCH (site/src/app/api/jobs/[id]/complete) fires, from the
// job's linked route_scans session (see site/src/lib/block-completion-report.ts).
// One report per job; regenerable by admin (upsert on jobId) if generation
// failed or needs a re-run after a v1 heuristic upgrade. `sessionId` is the
// route_scans.id the report was built from, kept for traceability/debugging
// even though jobId is the stable lookup key clients use.
export const blockCompletionReports = pgTable('block_completion_reports', {
  id: serial('id').primaryKey(),
  jobId: integer('job_id').notNull(),
  routeId: integer('route_id').notNull(),
  workerId: integer('worker_id').notNull(),
  sessionId: integer('session_id').notNull(), // route_scans.id this report was built from

  startedAt: timestamp('started_at', { mode: 'string', withTimezone: true }),
  completedAt: timestamp('completed_at', { mode: 'string', withTimezone: true }),
  durationSeconds: integer('duration_seconds'),

  // GeoJSON-ish track data: { scout: LineString | null, clean: LineString | null }
  gpsTrack: jsonb('gps_track'),
  // Array of { locationCellId, lat, lng, domain, observationCount } — see
  // queryAreasCleaned in block-completion-report.ts.
  areasCleaned: jsonb('areas_cleaned'),
  // Array of { locationCellId, itemType, before, after } before/after photo pairs
  // — see pairBeforeAfterPhotos in block-completion-report.ts.
  imagePairs: jsonb('image_pairs'),

  totalEstimatedWeightLbs: doublePrecision('total_estimated_weight_lbs'),
  cleanedEstimatedWeightLbs: doublePrecision('cleaned_estimated_weight_lbs'),
  // { byType: Record<string, number>, bySeverity: Record<string, number> }
  countsByType: jsonb('counts_by_type'),

  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  jobIdx: uniqueIndex('block_completion_reports_job_id_idx').on(table.jobId),
  routeIdx: index('block_completion_reports_route_id_idx').on(table.routeId),
  workerIdx: index('block_completion_reports_worker_id_idx').on(table.workerId),
}))

export type BlockCompletionReportRow = typeof blockCompletionReports.$inferSelect
export type NewBlockCompletionReportRow = typeof blockCompletionReports.$inferInsert

// Building footprints (scan-visibility rework Workstream 1): real OSM building
// polygons backing the indoor gate's map-containment signal. One row per OSM
// building way; bbox columns are indexed for cheap intersection lookup, the
// full ring lives in `polygon` as GeoJSON [lng,lat][]. Lazily fetched from
// Overpass on a tile cache-miss (see GET /api/map/buildings). Additive, no FKs
// (osm_id is the natural key), matching the hot-path-lookup pattern used by
// scan_coverage_events / world_observations elsewhere in this file.
export const buildingFootprints = pgTable('building_footprints', {
  id: serial('id').primaryKey(),
  osmId: bigint('osm_id', { mode: 'number' }), // OSM way id; natural upsert key
  source: varchar('source', { length: 16 }).default('overpass').notNull(),
  tileKey: varchar('tile_key', { length: 32 }).notNull(), // "b:{latIdx}:{lngIdx}"
  minLng: doublePrecision('min_lng').notNull(),
  minLat: doublePrecision('min_lat').notNull(),
  maxLng: doublePrecision('max_lng').notNull(),
  maxLat: doublePrecision('max_lat').notNull(),
  polygon: jsonb('polygon').notNull(), // GeoJSON linear ring: [lng,lat][]
  buildingType: varchar('building_type', { length: 64 }), // OSM building=* tag value
  fetchedAt: timestamp('fetched_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  bboxIdx: index('building_footprints_bbox_idx').on(table.minLng, table.maxLng, table.minLat, table.maxLat),
  tileIdx: index('building_footprints_tile_idx').on(table.tileKey),
  sourceOsmIdx: uniqueIndex('building_footprints_source_osm_idx').on(table.source, table.osmId),
}))

export type BuildingFootprintRow = typeof buildingFootprints.$inferSelect
export type NewBuildingFootprintRow = typeof buildingFootprints.$inferInsert

// Per-tile fetch ledger: records that a ~1.1km tile was fetched from Overpass
// and when, so a tile (INCLUDING an empty one with zero footprints) is fetched
// at most once per TTL. Without this, empty tiles would re-hit Overpass on
// every request.
export const buildingFootprintTiles = pgTable('building_footprint_tiles', {
  tileKey: varchar('tile_key', { length: 32 }).primaryKey(),
  fetchedAt: timestamp('fetched_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  footprintCount: integer('footprint_count').default(0).notNull(),
})

export type BuildingFootprintTileRow = typeof buildingFootprintTiles.$inferSelect
export type NewBuildingFootprintTileRow = typeof buildingFootprintTiles.$inferInsert

// Single-writer leases for the cron endpoints. Needed once an external
// scheduler drives them more often than once a day: `processDueBatches()` does
// no locking, so overlapping ticks would double-issue offers. `expires_at`
// (rather than a boolean) lets a crashed run self-heal. See lib/cron-lease.ts.
export const cronLeases = pgTable('cron_leases', {
  name: varchar('name', { length: 64 }).primaryKey(),
  lockedAt: timestamp('locked_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  expiresAt: timestamp('expires_at', { mode: 'string', withTimezone: true }).notNull(),
})

export type CronLeaseRow = typeof cronLeases.$inferSelect
export type NewCronLeaseRow = typeof cronLeases.$inferInsert

// Fixed-window rate-limit hits for the public auth endpoints (audit F-06).
// One row per request; lib/rate-limit.ts prunes rows older than the window
// for the key being checked, so the table stays small without a sweeper.
export const rateLimitEvents = pgTable('rate_limit_events', {
  id: bigserial('id', { mode: 'number' }).primaryKey(),
  key: varchar('key', { length: 255 }).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type RateLimitEventRow = typeof rateLimitEvents.$inferSelect
export type NewRateLimitEventRow = typeof rateLimitEvents.$inferInsert

// SMS verification codes (audit F-03). Durable replacement for the old
// in-memory Map — serverless instances share no memory, so a code requested
// on instance A could never be verified on instance B. `failedAttempts`
// locks a code out after repeated wrong guesses; `lastSentAt` /
// `sendCount` + `windowStartedAt` back the resend cooldown and the
// per-phone send quota. See lib/phone-verification.ts.
export const phoneVerificationCodes = pgTable('phone_verification_codes', {
  phone: varchar('phone', { length: 20 }).primaryKey(),
  code: varchar('code', { length: 6 }).notNull(),
  expiresAt: timestamp('expires_at', { mode: 'string', withTimezone: true }).notNull(),
  failedAttempts: integer('failed_attempts').default(0).notNull(),
  sendCount: integer('send_count').default(0).notNull(),
  windowStartedAt: timestamp('window_started_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  lastSentAt: timestamp('last_sent_at', { mode: 'string', withTimezone: true }),
})

export type PhoneVerificationCodeRow = typeof phoneVerificationCodes.$inferSelect
export type NewPhoneVerificationCodeRow = typeof phoneVerificationCodes.$inferInsert

// Android closed-test tester signups (/android-beta). Google Play requires 12
// opted-in testers for 14 days before a personal developer account may
// publish. A row is one Google account address; an admin copies new ones into
// the Play Console tester list and sends the opt-in link from
// /admin/android-testers. Status values and transitions: lib/android-testers.ts.
export const androidTesterSignups = pgTable('android_tester_signups', {
  id: serial('id').primaryKey(),
  email: varchar('email', { length: 254 }).notNull().unique(),
  name: varchar('name', { length: 120 }),
  // Where on the site the signup came from (e.g. 'android-beta', 'hero-banner').
  source: varchar('source', { length: 64 }),
  status: varchar('status', { length: 16 }).default('new').notNull(),
  addedAt: timestamp('added_at', { mode: 'string', withTimezone: true }),
  inviteSentAt: timestamp('invite_sent_at', { mode: 'string', withTimezone: true }),
  joinedAt: timestamp('joined_at', { mode: 'string', withTimezone: true }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type AndroidTesterSignupRow = typeof androidTesterSignups.$inferSelect
export type NewAndroidTesterSignupRow = typeof androidTesterSignups.$inferInsert
