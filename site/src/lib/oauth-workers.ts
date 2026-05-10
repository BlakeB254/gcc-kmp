import { db } from '@/db'
import { sponsors, waitlistEntries, workers } from '@/db/schema'
import { getEarlyAdopterTier, generateReferralCode } from '@/lib/movement-rankings'
import { eq, sql } from 'drizzle-orm'

type WorkerRow = typeof workers.$inferSelect

export interface ResolveOAuthWorkerInput {
  provider: 'google' | 'apple'
  providerUserId: string
  email: string
  emailVerified: boolean
  name?: string | null
}

async function createUniqueReferralCode(): Promise<string> {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const referralCode = generateReferralCode()
    const existing = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.referralCode, referralCode))
      .limit(1)

    if (existing.length === 0) return referralCode
  }

  return `GCC${Math.floor(100000 + Math.random() * 900000)}`
}

async function nextMovementRank() {
  const [
    [{ waitlistCount }],
    [{ workersCount }],
    [{ sponsorsCount }],
  ] = await Promise.all([
    db.select({ waitlistCount: sql<number>`count(*)` }).from(waitlistEntries),
    db.select({ workersCount: sql<number>`count(*)` }).from(workers),
    db.select({ sponsorsCount: sql<number>`count(*)` }).from(sponsors),
  ])

  return Number(waitlistCount) + Number(workersCount) + Number(sponsorsCount) + 1
}

export async function resolveOAuthWorker(input: ResolveOAuthWorkerInput): Promise<WorkerRow> {
  const email = input.email.replace(/\s/g, '').toLowerCase()
  if (!email || !email.includes('@') || !input.emailVerified) {
    throw new Error('OAuth provider did not return a verified email address')
  }

  const now = new Date().toISOString()

  if (input.provider === 'apple') {
    const [existingByAppleId] = await db
      .select()
      .from(workers)
      .where(eq(workers.appleUserId, input.providerUserId))
      .limit(1)

    if (existingByAppleId) return existingByAppleId
  }

  const [existingByEmail] = await db
    .select()
    .from(workers)
    .where(eq(workers.email, email))
    .limit(1)

  if (existingByEmail) {
    if (
      input.provider === 'apple' &&
      existingByEmail.appleUserId &&
      existingByEmail.appleUserId !== input.providerUserId
    ) {
      throw new Error('This email is already linked to a different Apple account')
    }

    const [updated] = await db
      .update(workers)
      .set({
        appleUserId: input.provider === 'apple' ? input.providerUserId : existingByEmail.appleUserId,
        emailVerified: true,
        emailVerifiedAt: existingByEmail.emailVerifiedAt || now,
        updatedAt: now,
      })
      .where(eq(workers.id, existingByEmail.id))
      .returning()

    if (!updated) throw new Error('Failed to update OAuth worker')
    return updated
  }

  const rank = await nextMovementRank()
  const earlyAdopterTier = getEarlyAdopterTier(rank)
  const referralCode = await createUniqueReferralCode()
  const name = input.name?.trim() || email.split('@')[0]

  const [inserted] = await db
    .insert(workers)
    .values({
      name,
      email,
      phone: '',
      salt: null,
      hash: null,
      appleUserId: input.provider === 'apple' ? input.providerUserId : null,
      emailVerified: true,
      emailVerifiedAt: now,
      addressStreet: '',
      addressCity: '',
      addressState: '',
      addressZip: '',
      verificationStatus: 'pending',
      verificationGovernmentIdStatus: 'pending',
      verificationProofOfAddressStatus: 'pending',
      stripeOnboardingStatus: 'not_started',
      stripeInstantPayoutsEnabled: false,
      stripeDefaultPayoutMethod: 'standard',
      statsJobsCompleted: '0',
      statsTotalEarnings: '0',
      signupTimestamp: now,
      signupRankingsGlobal: String(rank),
      signupRankingsCountry: String(rank),
      signupRankingsState: '0',
      signupRankingsCity: '0',
      signupRankingsNeighborhood: '0',
      earlyAdopterTier,
      referralCode,
      notificationsPushEnabled: false,
      notificationsSmsEnabled: true,
      notificationsEmailEnabled: true,
      createdAt: now,
      updatedAt: now,
    })
    .returning()

  if (!inserted) throw new Error('Failed to create OAuth worker')
  return inserted
}
