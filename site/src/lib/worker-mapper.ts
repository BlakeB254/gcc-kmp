/** Convert a Postgres timestamp string to ISO 8601 format for kotlinx.datetime */
function toIso8601(ts: unknown): string {
  if (!ts) return new Date().toISOString()
  const str = String(ts)
  // Postgres returns "2026-03-07 03:54:34.968+00" — needs T separator and full TZ offset
  return new Date(str).toISOString()
}

/**
 * Maps a flat DB worker row into the nested Worker shape the mobile app expects.
 */
export function mapWorkerToMobile(row: Record<string, unknown>) {
  return {
    id: row.id,
    name: row.name,
    email: row.email,
    phone: row.phone,
    emailVerified: row.emailVerified ?? false,
    address: {
      street: row.addressStreet ?? '',
      city: row.addressCity ?? 'Chicago',
      state: row.addressState ?? 'IL',
      zip: row.addressZip ?? '',
      neighborhood: row.addressNeighborhood ?? null,
      isNorthLawndale: row.addressIsNorthLawndale ?? null,
    },
    verification: {
      status: row.verificationStatus ?? 'pending',
      governmentIdStatus: row.verificationGovernmentIdStatus ?? 'pending',
      proofOfAddressStatus: row.verificationProofOfAddressStatus ?? 'pending',
      reviewNotes: row.verificationReviewNotes ?? null,
    },
    stripe: {
      accountId: row.stripeAccountId ?? null,
      onboardingStatus: row.stripeOnboardingStatus ?? 'not_started',
      instantPayoutsEnabled: row.stripeInstantPayoutsEnabled ?? false,
      defaultPayoutMethod: row.stripeDefaultPayoutMethod ?? 'standard',
    },
    // Ad-hoc payout routing preference: 'stripe' | 'check'
    payoutMethod: row.payoutMethod ?? 'stripe',
    stats: {
      jobsCompleted: Number(row.statsJobsCompleted ?? 0),
      totalEarnings: Number(row.statsTotalEarnings ?? 0),
      averageRating: row.statsAverageRating ? Number(row.statsAverageRating) : null,
      lastJobAt: row.statsLastJobAt ? toIso8601(row.statsLastJobAt) : null,
      communityPoints: Number(row.statsCommunityPoints ?? 0),
    },
    earlyAdopterTier: row.earlyAdopterTier ?? 'standard',
    role: 'worker',
    referralCode: row.referralCode ?? null,
    displayHandle: row.displayHandle ?? null,
    memberNumber: Number(row.memberNumber ?? 0),
    signupRankings: {
      global: Number(row.signupRankingsGlobal ?? 0),
      state: Number(row.signupRankingsState ?? 0),
      city: Number(row.signupRankingsCity ?? 0),
      neighborhood: Number(row.signupRankingsNeighborhood ?? 0),
    },
    notifications: {
      pushEnabled: row.notificationsPushEnabled ?? false,
      smsEnabled: row.notificationsSmsEnabled ?? true,
      emailEnabled: row.notificationsEmailEnabled ?? true,
    },
    createdAt: toIso8601(row.createdAt),
    updatedAt: toIso8601(row.updatedAt),
  }
}
