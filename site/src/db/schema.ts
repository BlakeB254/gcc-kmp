import {
  pgTable,
  serial,
  varchar,
  text,
  boolean,
  timestamp,
  numeric,
  integer,
  jsonb,
  pgEnum,
  doublePrecision,
  index,
  uniqueIndex,
} from 'drizzle-orm/pg-core'

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
  checkpoints: jsonb('checkpoints'), // Array of checkpoint objects

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
  beforePhotos: jsonb('before_photos'), // Array of media IDs
  afterPhotos: jsonb('after_photos'), // Array of media IDs
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
}, (table) => ({
  // Hot-path indexes — added during the M-6 audit pass.
  // Postgres does NOT auto-index foreign-key columns; these are queried
  // constantly ("jobs for worker X", "jobs on block Y", filtered by status).
  workerIdx: index('jobs_worker_id_idx').on(table.workerId),
  blockIdx: index('jobs_block_id_idx').on(table.blockId),
  statusIdx: index('jobs_status_idx').on(table.status),
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
  acceptedByWorkerId: integer('accepted_by_worker_id'),
  acceptedOfferId: integer('accepted_offer_id'),
  acceptedAt: timestamp('accepted_at', { mode: 'string', withTimezone: true }),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  batchRouteUnique: uniqueIndex('idx_route_release_routes_batch_route_unique').on(table.batchId, table.routeId),
  jobUnique: uniqueIndex('idx_route_release_routes_job_unique').on(table.jobId),
  batchStatusIdx: index('idx_route_release_routes_batch_status').on(table.batchId, table.status),
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
}))

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
})

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
  'scan_coverage_rectification', // Confirmed a previously-expected-dirty spot is now clean
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
  publishedAt: timestamp('published_at', { mode: 'string', withTimezone: true }),
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

// Checkpoint type enum
export const checkpointTypeEnum = pgEnum('enum_route_checkpoints_type', [
  'route_start',
  'photo_point',
  'alley_entrance',
  'route_end',
])

// Route checkpoints table (photo verification points)
export const routeCheckpoints = pgTable('route_checkpoints', {
  id: serial('id').primaryKey(),
  routeId: integer('route_id').notNull(), // FK to routes

  name: varchar('name', { length: 100 }).notNull(),
  sequenceOrder: integer('sequence_order').notNull(),

  // GPS coordinates [lng, lat]
  coordinates: jsonb('coordinates').notNull(),

  // Photo configuration
  photoRequired: boolean('photo_required').default(true),
  photoInstructions: text('photo_instructions'),

  // Geofence radius in meters
  geofenceRadius: numeric('geofence_radius').default('15'),

  // Type of checkpoint
  type: checkpointTypeEnum('type').notNull(),

  // Timestamps
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

// Route scan session table (mobile Scout & Clean flow)
export const routeScans = pgTable('route_scans', {
  id: serial('id').primaryKey(),
  routeId: integer('route_id').notNull(),
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
})

// Hotspots recorded during route scan sessions
export const scanHotspots = pgTable('scan_hotspots', {
  id: serial('id').primaryKey(),
  scanId: integer('scan_id').notNull(),
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  trashTypes: varchar('trash_types', { length: 500 }).default('[]').notNull(),
  severity: integer('severity').default(1).notNull(),
  estimatedVolumeLiters: doublePrecision('estimated_volume_liters'),
  estimatedWeightLbs: doublePrecision('estimated_weight_lbs'),
  confidence: doublePrecision('confidence').default(0).notNull(),
  beforePhotoUrl: varchar('before_photo_url', { length: 500 }).notNull(),
  afterPhotoUrl: varchar('after_photo_url', { length: 500 }),
  arAnchorData: text('ar_anchor_data'),
  depthData: text('depth_data'),
  detectedAt: timestamp('detected_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  cleanedAt: timestamp('cleaned_at', { mode: 'string', withTimezone: true }),
  verificationScore: doublePrecision('verification_score'),
  status: varchar('status', { length: 20 }).default('detected').notNull(),
})

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
export type RouteCheckpoint = typeof routeCheckpoints.$inferSelect
export type NewRouteCheckpoint = typeof routeCheckpoints.$inferInsert
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
  sourceType: varchar('source_type', { length: 20 }).notNull(), // 'route_scan' | 'spot_report'
  domain: varchar('domain', { length: 20 }).default('litter').notNull(), // 'litter' | 'vegetation' | 'construction' | 'land_use'
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  itemType: varchar('item_type', { length: 50 }), // null when state='clean' (nothing dirty found)
  state: varchar('state', { length: 10 }).notNull(), // 'dirty' | 'clean'
  attributes: jsonb('attributes'), // domain-specific payload, e.g. litter: {severity, estimatedVolumeLiters, estimatedWeightLbs}
  confidence: doublePrecision('confidence'),
  mediaUrl: varchar('media_url', { length: 500 }),
  depthData: text('depth_data'),
  arAnchorData: text('ar_anchor_data'),
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }).notNull(),
  isCurrent: boolean('is_current').default(true).notNull(),
  excludedPrivateProperty: boolean('excluded_private_property').default(false).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

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
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  cellCapturedIdx: index('scan_coverage_events_cell_captured_idx').on(table.locationCellId, table.capturedAt),
}))

export type ScanCoverageEventRow = typeof scanCoverageEvents.$inferSelect
export type NewScanCoverageEventRow = typeof scanCoverageEvents.$inferInsert

export const cellCoverageSummary = pgTable('cell_coverage_summary', {
  locationCellId: varchar('location_cell_id', { length: 32 }).primaryKey(),
  firstScannedAt: timestamp('first_scanned_at', { mode: 'string', withTimezone: true }).notNull(),
  lastScannedAt: timestamp('last_scanned_at', { mode: 'string', withTimezone: true }).notNull(),
  totalDistanceMeters: doublePrecision('total_distance_meters').default(0).notNull(),
  scanCount: integer('scan_count').default(0).notNull(),
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

export type CommunityReport = typeof communityReports.$inferSelect
export type NewCommunityReport = typeof communityReports.$inferInsert
export type CommunityReportThanks = typeof communityReportThanks.$inferSelect
export type NewCommunityReportThanks = typeof communityReportThanks.$inferInsert
export type MaterialDonation = typeof materialDonations.$inferSelect
export type NewMaterialDonation = typeof materialDonations.$inferInsert
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
