import { stripe, STRIPE_CONFIG, isStripeConfigured } from './client'

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
      url: process.env.NEXT_PUBLIC_APP_URL,
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
 * Transfer funds from platform to worker's Connect account
 * This is step 1 of the payout process
 */
export async function createTransferToWorker(
  accountId: string,
  amountCents: number,
  jobId: string
) {
  const transfer = await stripe.transfers.create({
    amount: amountCents,
    currency: 'usd',
    destination: accountId,
    metadata: {
      jobId,
      platform: 'gcc',
      type: 'job_payment',
    },
    description: `GCC Job Payment - ${jobId}`,
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
  jobId: string
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
        jobId,
        platform: 'gcc',
        type: 'instant_payout',
        originalAmount: amountCents.toString(),
        instantFee: instantFee.toString(),
      },
      description: `GCC Instant Payout - ${jobId}`,
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
  jobId: string
) {
  const payout = await stripe.payouts.create(
    {
      amount: amountCents,
      currency: 'usd',
      method: 'standard',
      metadata: {
        jobId,
        platform: 'gcc',
        type: 'standard_payout',
      },
      description: `GCC Payout - ${jobId}`,
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
export async function processJobPayout(
  accountId: string,
  amountCents: number,
  jobId: string,
  useInstant: boolean = true
) {
  // Step 1: Transfer funds to worker's Connect account
  const transfer = await createTransferToWorker(accountId, amountCents, jobId)

  // Step 2: Initiate payout from Connect account to bank
  let payoutResult
  if (useInstant) {
    try {
      payoutResult = await createInstantPayout(accountId, amountCents, jobId)
    } catch (error) {
      // Fall back to standard if instant fails
      console.warn('Instant payout failed, falling back to standard:', error)
      payoutResult = await createStandardPayout(accountId, amountCents, jobId)
    }
  } else {
    payoutResult = await createStandardPayout(accountId, amountCents, jobId)
  }

  return {
    transfer,
    ...payoutResult,
  }
}
