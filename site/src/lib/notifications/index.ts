import webpush from 'web-push'

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

interface NotificationPayload {
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

  // Sponsor notifications
  blockCleaned: (blockCode: string) => ({
    title: 'Your Block is Clean! ✨',
    body: `A GCC worker just finished cleaning ${blockCode}. Check your email for photos!`,
    url: '/sponsor/impact',
  }),
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
