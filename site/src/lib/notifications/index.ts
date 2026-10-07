import webpush from 'web-push'
import { eq, inArray } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { sendApns, apnsConfigured } from './apns'

// Initialize web-push with VAPID keys
if (process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY) {
  webpush.setVapidDetails(
    'mailto:support@globalcommunitycleaners.org',
    process.env.VAPID_PUBLIC_KEY,
    process.env.VAPID_PRIVATE_KEY
  )
}

interface PushSubscription {
  endpoint: string
  keys: {
    p256dh: string
    auth: string
  }
}

export interface NotificationPayload {
  title: string
  body: string
  icon?: string
  badge?: string
  url?: string
  tag?: string
  data?: Record<string, unknown>
}

/**
 * Send push notification to a subscribed device
 */
export async function sendPushNotification(
  subscription: PushSubscription,
  payload: NotificationPayload
): Promise<boolean> {
  if (!process.env.VAPID_PUBLIC_KEY || !process.env.VAPID_PRIVATE_KEY) {
    console.warn('VAPID keys not configured, skipping push notification')
    return false
  }

  try {
    await webpush.sendNotification(
      subscription,
      JSON.stringify({
        ...payload,
        icon: payload.icon || '/icons/icon-192.png',
        badge: payload.badge || '/icons/badge-72.png',
      })
    )
    return true
  } catch (error) {
    console.error('Failed to send push notification:', error)
    return false
  }
}

/**
 * Send SMS notification via Twilio
 */
export async function sendSMS(
  to: string,
  message: string
): Promise<boolean> {
  const accountSid = process.env.TWILIO_ACCOUNT_SID
  const authToken = process.env.TWILIO_AUTH_TOKEN
  const fromNumber = process.env.TWILIO_PHONE_NUMBER

  if (!accountSid || !authToken || !fromNumber) {
    console.warn('Twilio credentials not configured, skipping SMS')
    return false
  }

  try {
    const response = await fetch(
      `https://api.twilio.com/2010-04-01/Accounts/${accountSid}/Messages.json`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          Authorization: `Basic ${Buffer.from(`${accountSid}:${authToken}`).toString('base64')}`,
        },
        body: new URLSearchParams({
          From: fromNumber,
          To: to,
          Body: message,
        }),
      }
    )

    if (!response.ok) {
      const error = await response.text()
      console.error('Twilio error:', error)
      return false
    }

    return true
  } catch (error) {
    console.error('Failed to send SMS:', error)
    return false
  }
}

/**
 * Notification templates for common events
 */
export const notificationTemplates = {
  // Worker notifications
  applicationApproved: (workerName: string) => ({
    title: 'Application Approved! 🎉',
    body: `Welcome to GCC, ${workerName}! Complete your Stripe setup to start earning.`,
    url: '/worker/approved',
  }),

  blockAvailable: (blockCode: string) => ({
    title: 'New Block Available! 🧹',
    body: `Block ${blockCode} is ready to claim. Earn $100 for cleaning it!`,
    url: '/worker/dashboard',
  }),

  jobApproved: (blockCode: string) => ({
    title: 'Job Approved! 💰',
    body: `Great work on ${blockCode}! Your $100 payout is being processed.`,
    url: '/worker/dashboard',
  }),

  payoutSent: (amount: string) => ({
    title: 'Money on the way! 🏦',
    body: `${amount} has been sent to your bank. Expect it within 30 minutes.`,
    url: '/worker/dashboard',
  }),

  walletCredited: (amount: string, reason: string) => ({
    title: 'You got paid! 💰',
    body: `${amount} for ${reason} is in your GCC wallet. Cash out any time from the Wallet tab.`,
    url: '/worker/dashboard',
  }),

  withdrawalFailed: (amount: string) => ({
    title: 'Cash out failed ⚠️',
    body: `Your ${amount} cash out could not be completed. The money is back in your GCC wallet — try again or contact support.`,
    url: '/worker/dashboard',
  }),

  withdrawalBankFailed: (amount: string) => ({
    title: 'Cash out did not reach your bank ⚠️',
    body: `Your ${amount} cash out could not be deposited. The money is safe in your Stripe account — check your bank details in Stripe, and our team will retry it.`,
    url: '/worker/dashboard',
  }),

  payoutMethodChanged: () => ({
    title: 'Your payout details changed',
    body: 'How you get paid on GCC was just changed. For your security, cash outs are paused for 24 hours. If this was not you, contact support right away.',
    url: '/worker/dashboard',
  }),

  // Sponsor notifications
  blockCleaned: (blockCode: string) => ({
    title: 'Your Block is Clean! ✨',
    body: `A GCC worker just finished cleaning ${blockCode}. Check your email for photos!`,
    url: '/sponsor/impact',
  }),

  checkMailed: (amount: string) => ({
    title: 'Your check is in the mail 📬',
    body: `Your check for ${amount} has been mailed. Allow 5–7 business days for delivery.`,
    url: '/worker/dashboard',
  }),

  // Admin notifications
  payoutNeedsApproval: (workerName: string, jobId: number, amountDollars: string) => ({
    title: 'Payout Approval Needed 💰',
    body: `${workerName} submitted job #${jobId} for review — $${amountDollars} pending approval.`,
    url: '/admin',
    tag: `payout-approval-${jobId}`,
    data: { jobId },
  }),

  // Paid-job TTL policy (lib/paid-job-ttl.ts) — worker side
  jobStartReminder: (jobId: number, hoursLeft: number) => ({
    title: 'Your cleanup is waiting 🧹',
    body: `You claimed job #${jobId} but haven't started it. Start within ${hoursLeft} hours or it goes back to other workers.`,
    url: '/worker/dashboard',
    tag: `job-start-reminder-${jobId}`,
    data: { type: 'job_start_reminder', jobId, deep_link: '/dashboard' },
  }),

  jobInProgressReminder: (jobId: number) => ({
    title: 'Finish your cleanup scan',
    body: `Job #${jobId} has been in progress for a while. Finish the cleaning lap and submit your scan so it can be reviewed.`,
    url: '/worker/dashboard',
    tag: `job-progress-reminder-${jobId}`,
    data: { type: 'job_progress_reminder', jobId, deep_link: '/dashboard' },
  }),

  // Paid-job TTL policy — admin side
  jobAutoSubmittedForReview: (jobId: number) => ({
    title: 'Job moved to review',
    body: `Job #${jobId} was in progress 24h with a verified completed scan, so it was moved to review automatically.`,
    url: '/admin',
    tag: `payout-approval-${jobId}`,
    data: { jobId },
  }),

  jobStalled: (jobId: number, workerLabel: string) => ({
    title: 'Paid job stalled ⚠️',
    body: `Job #${jobId} (${workerLabel}) has been in progress 24h with no verified scan. It was NOT released — check on it in Operations.`,
    url: '/admin',
    tag: `job-stalled-${jobId}`,
    data: { jobId, type: 'job_stalled' },
  }),

  checkToMail: (workerName: string, amountDollars: string, payoutId: number) => ({
    title: 'New check to mail 📬',
    body: `$${amountDollars} payout to ${workerName} is waiting in the checks-to-mail queue.`,
    url: '/admin',
    tag: `check-payout-${payoutId}`,
    data: { type: 'check_payout', payoutId, deep_link: '/dashboard' },
  }),
}

/**
 * Best-effort push to every admin (ADMIN_EMAILS) with push enabled. Handles
 * both stored subscription shapes: VAPID web-push subscriptions
 * ({ endpoint, keys }) and native mobile tokens ({ token, platform } → APNs).
 * Never throws — money paths call this fire-and-forget.
 */
export async function notifyAdmins(notification: NotificationPayload): Promise<void> {
  try {
    const adminEmails = (process.env.ADMIN_EMAILS || '')
      .split(',')
      .map((e) => e.trim().toLowerCase())
      .filter(Boolean)
    if (adminEmails.length === 0) return

    const adminWorkers = await db
      .select({
        pushEnabled: workers.notificationsPushEnabled,
        pushSubscription: workers.notificationsPushSubscription,
      })
      .from(workers)
      .where(inArray(workers.email, adminEmails))

    const sends = adminWorkers
      .filter((w) => w.pushEnabled && w.pushSubscription)
      .map((w) => sendToStoredSubscription(w.pushSubscription, notification))

    await Promise.allSettled(sends)
  } catch (err) {
    console.error('[notifications] notifyAdmins failed:', err)
  }
}

/**
 * Push to one stored `workers.notifications_push_subscription`, whichever
 * shape it has: a VAPID web-push subscription ({ endpoint, keys }) or a
 * native mobile token ({ token, platform } → APNs).
 */
function sendToStoredSubscription(stored: unknown, notification: NotificationPayload): Promise<boolean> {
  const sub = (stored ?? {}) as Record<string, unknown>
  if (
    typeof sub.endpoint === 'string' &&
    sub.keys &&
    typeof (sub.keys as Record<string, unknown>).p256dh === 'string' &&
    typeof (sub.keys as Record<string, unknown>).auth === 'string'
  ) {
    return sendPushNotification(sub as unknown as PushSubscription, notification)
  }
  if (typeof sub.token === 'string' && sub.token && apnsConfigured()) {
    return sendApns(sub.token, {
      title: notification.title,
      body: notification.body,
      data: notification.data as Record<string, string | number | boolean> | undefined,
    }).then((r) => r.success)
  }
  return Promise.resolve(false)
}

/**
 * Best-effort push to one worker by id (web-push or APNs). `notifyWorker`
 * below only handles the web-push shape, which mobile workers don't have.
 * Never throws; returns whether a push was handed to a provider.
 */
export async function pushToWorker(workerId: number, notification: NotificationPayload): Promise<boolean> {
  try {
    const [worker] = await db
      .select({
        pushEnabled: workers.notificationsPushEnabled,
        pushSubscription: workers.notificationsPushSubscription,
      })
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)
    if (!worker?.pushEnabled || !worker.pushSubscription) return false
    return await sendToStoredSubscription(worker.pushSubscription, notification)
  } catch (err) {
    console.error(`[notifications] pushToWorker(${workerId}) failed:`, err)
    return false
  }
}

/**
 * Send notification to worker via their preferred method
 */
export async function notifyWorker(
  worker: {
    phone?: string
    pushSubscription?: PushSubscription
    preferences?: {
      smsEnabled?: boolean
      pushEnabled?: boolean
    }
  },
  notification: NotificationPayload
): Promise<void> {
  const promises: Promise<boolean>[] = []

  // Send push notification if enabled and subscription exists
  if (worker.preferences?.pushEnabled !== false && worker.pushSubscription) {
    promises.push(sendPushNotification(worker.pushSubscription, notification))
  }

  // Send SMS if enabled and phone exists
  if (worker.preferences?.smsEnabled !== false && worker.phone) {
    const smsMessage = `GCC: ${notification.title}\n${notification.body}`
    promises.push(sendSMS(worker.phone, smsMessage))
  }

  await Promise.all(promises)
}
