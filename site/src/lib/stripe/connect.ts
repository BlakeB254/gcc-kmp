import { stripe, STRIPE_CONFIG, isStripeConfigured } from './client'
import { appUrl } from '@/lib/env'

/**
 * Create a Stripe Connect Express account for a worker
 * Returns null if Stripe is not configured (for development/demo)
 */
export async function createConnectAccount(workerId: string, email: string) {
  if (!isStripeConfigured) {
    console.log('[Stripe] Skipping Connect account creation - Stripe not configured')
    return { id: `demo_acct_${workerId}` }
  }

  const account = await stripe.accounts.create({
    type: 'express',
    country: 'US',
    email,
    capabilities: {
      transfers: { requested: true },
    },
    business_type: 'individual',
    business_profile: {
      mcc: '7349', // Building cleaning and maintenance services
      url: appUrl(),
    },
    metadata: {
      workerId,
      platform: 'gcc',
    },
  })

  return account
}

/**
 * Create an onboarding link for a worker to complete Stripe verification
 */
export async function createOnboardingLink(
  accountId: string,
  returnUrl: string,
  refreshUrl: string
) {
  const accountLink = await stripe.accountLinks.create({
    account: accountId,
    refresh_url: refreshUrl,
    return_url: returnUrl,
    type: 'account_onboarding',
  })

  return accountLink.url
}

/**
 * Check if a Connect account is fully onboarded and can receive payouts
 */
export async function checkAccountStatus(accountId: string) {
  const account = await stripe.accounts.retrieve(accountId)

  return {
    accountId: account.id,
    chargesEnabled: account.charges_enabled,
    payoutsEnabled: account.payouts_enabled,
    detailsSubmitted: account.details_submitted,
    requirements: account.requirements,
    instantPayoutsEnabled:
      (account.capabilities as Record<string, string>)?.instant_payouts === 'active' ||
      (account.capabilities as Record<string, string>)?.us_bank_account_ach_payments === 'active',
  }
}

/**
 * Create a login link for workers to access their Stripe Express dashboard
 */
export async function createDashboardLink(accountId: string) {
  const loginLink = await stripe.accounts.createLoginLink(accountId)
  return loginLink.url
}

/**
 * What a transfer/payout is for. The webhook handler reconciles payout.paid /
 * payout.failed events back to a DB row via this metadata: jobs by
 * metadata.jobId, ad-hoc admin payouts by metadata.payoutId. workerId lets
 * the webhook bump worker earnings stats without a second lookup.
 */
export type PayoutReference =
  | { kind: 'job'; jobId: string; workerId?: string }
  | { kind: 'admin_payout'; payoutId: string; workerId: string }

function referenceMetadata(ref: PayoutReference): Record<string, string> {
  if (ref.kind === 'job') {
    return {
      jobId: ref.jobId,
      ...(ref.workerId ? { workerId: ref.workerId } : {}),
      platform: 'gcc',
    }
  }
  return {
    payoutId: ref.payoutId,
    workerId: ref.workerId,
    platform: 'gcc',
  }
}

function referenceLabel(ref: PayoutReference): string {
  return ref.kind === 'job' ? ref.jobId : `payout ${ref.payoutId}`
}

/**
 * Transfer funds from platform to worker's Connect account
 * This is step 1 of the payout process
 */
export async function createTransferToWorker(
  accountId: string,
  amountCents: number,
  ref: PayoutReference
) {
  const transfer = await stripe.transfers.create({
    amount: amountCents,
    currency: 'usd',
    destination: accountId,
    metadata: {
      ...referenceMetadata(ref),
      type: ref.kind === 'job' ? 'job_payment' : 'admin_payout',
    },
    description:
      ref.kind === 'job'
        ? `GCC Job Payment - ${ref.jobId}`
        : `GCC Admin Payout - ${ref.payoutId}`,
  })

  return transfer
}

/**
 * Initiate an instant payout from worker's Connect balance to their bank
 * This is step 2 - only works if instant payouts are enabled
 */
export async function createInstantPayout(
  accountId: string,
  amountCents: number,
  ref: PayoutReference
) {
  // Calculate instant payout fee (1% of amount)
  const instantFee = Math.ceil(amountCents * (STRIPE_CONFIG.instantPayoutFeePercent / 100))
  const netAmount = amountCents - instantFee

  // Create payout on the connected account
  const payout = await stripe.payouts.create(
    {
      amount: netAmount,
      currency: 'usd',
      method: 'instant',
      metadata: {
        ...referenceMetadata(ref),
        type: 'instant_payout',
        originalAmount: amountCents.toString(),
        instantFee: instantFee.toString(),
      },
      description: `GCC Instant Payout - ${referenceLabel(ref)}`,
    },
    {
      stripeAccount: accountId,
    }
  )

  return {
    payout,
    fees: {
      instantFee,
      netAmount,
    },
  }
}

/**
 * Create a standard payout (2-3 business days) for workers without instant payout
 */
export async function createStandardPayout(
  accountId: string,
  amountCents: number,
  ref: PayoutReference
) {
  const payout = await stripe.payouts.create(
    {
      amount: amountCents,
      currency: 'usd',
      method: 'standard',
      metadata: {
        ...referenceMetadata(ref),
        type: 'standard_payout',
      },
      description: `GCC Payout - ${referenceLabel(ref)}`,
    },
    {
      stripeAccount: accountId,
    }
  )

  return {
    payout,
    fees: {
      instantFee: 0,
      netAmount: amountCents,
    },
  }
}

/**
 * Full payout flow: Transfer + Payout in one operation
 */
export async function processPayout(
  accountId: string,
  amountCents: number,
  ref: PayoutReference,
  useInstant: boolean = true
) {
  // Step 1: Transfer funds to worker's Connect account
  const transfer = await createTransferToWorker(accountId, amountCents, ref)

  // Step 2: Initiate payout from Connect account to bank
  let payoutResult
  if (useInstant) {
    try {
      payoutResult = await createInstantPayout(accountId, amountCents, ref)
    } catch (error) {
      // Fall back to standard if instant fails
      console.warn('Instant payout failed, falling back to standard:', error)
      payoutResult = await createStandardPayout(accountId, amountCents, ref)
    }
  } else {
    payoutResult = await createStandardPayout(accountId, amountCents, ref)
  }

  return {
    transfer,
    ...payoutResult,
  }
}

/**
 * Job payout: kept as the original entry point so the job-approve route is
 * behavior-identical (metadata still keyed on jobId).
 */
export async function processJobPayout(
  accountId: string,
  amountCents: number,
  jobId: string,
  useInstant: boolean = true,
  workerId?: string
) {
  return processPayout(accountId, amountCents, { kind: 'job', jobId, workerId }, useInstant)
}

/**
 * Ad-hoc admin payout: metadata keyed on the payouts-table row id.
 */
export async function processAdminPayout(
  accountId: string,
  amountCents: number,
  payoutId: string,
  workerId: string,
  useInstant: boolean = true
) {
  return processPayout(
    accountId,
    amountCents,
    { kind: 'admin_payout', payoutId, workerId },
    useInstant
  )
}
