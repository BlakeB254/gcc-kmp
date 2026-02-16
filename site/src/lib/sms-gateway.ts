/**
 * Email-to-SMS Gateway Service
 *
 * Sends SMS via carrier email gateways (free, but less reliable than Twilio)
 * Best for: internal apps, testing, low-volume use cases
 *
 * How it works: Most carriers have email addresses that convert to SMS
 * Example: 3125551234@vtext.com -> SMS to that Verizon number
 */

import { Resend } from 'resend'

const resend = new Resend(process.env.RESEND_API_KEY)

// Carrier email-to-SMS gateways (US carriers)
// Format: {10-digit-number}@{gateway}
const CARRIER_GATEWAYS = {
  // Major carriers
  verizon: 'vtext.com',
  att: 'txt.att.net',
  tmobile: 'tmomail.net',
  sprint: 'messaging.sprintpcs.com',

  // Regional/MVNO carriers
  uscc: 'email.uscc.net',           // US Cellular
  cricket: 'sms.cricketwireless.net',
  metropcs: 'mymetropcs.com',
  boost: 'sms.myboostmobile.com',
  virgin: 'vmobl.com',
  googlefi: 'msg.fi.google.com',
  xfinity: 'vtext.com',             // Uses Verizon
  visible: 'vtext.com',             // Uses Verizon
  mint: 'tmomail.net',              // Uses T-Mobile

  // MMS gateways (sometimes more reliable, supports longer messages)
  verizon_mms: 'vzwpix.com',
  att_mms: 'mms.att.net',
  tmobile_mms: 'tmomail.net',
} as const

type CarrierKey = keyof typeof CARRIER_GATEWAYS

// Known number prefixes to carrier mappings (imperfect but helpful)
// Based on common area code assignments - not 100% accurate due to number portability
const PREFIX_HINTS: Record<string, CarrierKey[]> = {
  // This is a simplified heuristic - in production you'd use a carrier lookup API
  // For now, we try multiple gateways
}

export interface SMSResult {
  success: boolean
  carrier?: string
  error?: string
  messageId?: string
}

/**
 * Normalize phone to 10 digits
 */
function normalize10Digit(phone: string): string {
  const digits = phone.replace(/\D/g, '')
  // Remove leading 1 if present
  return digits.startsWith('1') && digits.length === 11
    ? digits.slice(1)
    : digits
}

/**
 * Send SMS via email gateway
 * Tries multiple carriers if specific carrier unknown
 */
export async function sendSMS(
  phone: string,
  message: string,
  options?: {
    carrier?: CarrierKey
    tryAll?: boolean
    fromName?: string
  }
): Promise<SMSResult> {
  const phoneDigits = normalize10Digit(phone)

  if (phoneDigits.length !== 10) {
    return { success: false, error: 'Invalid phone number - must be 10 digits' }
  }

  const fromName = options?.fromName || 'GCC'

  // If carrier specified, try just that one
  if (options?.carrier) {
    return await trySendToGateway(phoneDigits, message, options.carrier, fromName)
  }

  // Default: try the major carriers (most US numbers are on these 4)
  const majorCarriers: CarrierKey[] = ['verizon', 'att', 'tmobile', 'sprint']

  if (options?.tryAll) {
    // Try all major carriers in parallel (spammy but ensures delivery)
    const results = await Promise.all(
      majorCarriers.map(carrier => trySendToGateway(phoneDigits, message, carrier, fromName))
    )

    const successful = results.find(r => r.success)
    if (successful) {
      return successful
    }

    return {
      success: false,
      error: 'Failed to send via all carriers. Number may be invalid or carrier not supported.'
    }
  }

  // Default strategy: try carriers one by one until success
  // This is slower but less spammy
  for (const carrier of majorCarriers) {
    const result = await trySendToGateway(phoneDigits, message, carrier, fromName)
    if (result.success) {
      return result
    }
  }

  return {
    success: false,
    error: 'Could not deliver to any carrier gateway'
  }
}

/**
 * Send to a specific carrier gateway
 */
async function trySendToGateway(
  phone10: string,
  message: string,
  carrier: CarrierKey,
  fromName: string
): Promise<SMSResult> {
  const gateway = CARRIER_GATEWAYS[carrier]
  const toEmail = `${phone10}@${gateway}`

  try {
    const { data, error } = await resend.emails.send({
      from: `${fromName} <noreply@globalcommunitycleaners.org>`,
      to: toEmail,
      subject: '', // SMS gateways ignore subject, but some require it
      text: message,
    })

    if (error) {
      console.log(`SMS gateway ${carrier} failed:`, error.message)
      return { success: false, carrier, error: error.message }
    }

    console.log(`SMS sent via ${carrier} gateway to ${phone10}`)
    return { success: true, carrier, messageId: data?.id }

  } catch (err) {
    const errorMsg = err instanceof Error ? err.message : 'Unknown error'
    console.log(`SMS gateway ${carrier} error:`, errorMsg)
    return { success: false, carrier, error: errorMsg }
  }
}

/**
 * Send verification code via SMS gateway
 * Convenience wrapper with formatted message
 */
export async function sendVerificationCode(
  phone: string,
  code: string
): Promise<SMSResult> {
  const message = `Your GCC verification code is: ${code}\n\nThis code expires in 10 minutes.`

  // For verification codes, try all carriers to ensure delivery
  return await sendSMS(phone, message, { tryAll: true })
}

/**
 * List of supported carriers for UI display
 */
export const SUPPORTED_CARRIERS = [
  { id: 'verizon', name: 'Verizon' },
  { id: 'att', name: 'AT&T' },
  { id: 'tmobile', name: 'T-Mobile' },
  { id: 'sprint', name: 'Sprint' },
  { id: 'uscc', name: 'US Cellular' },
  { id: 'cricket', name: 'Cricket' },
  { id: 'metropcs', name: 'Metro by T-Mobile' },
  { id: 'boost', name: 'Boost Mobile' },
  { id: 'mint', name: 'Mint Mobile' },
  { id: 'googlefi', name: 'Google Fi' },
  { id: 'visible', name: 'Visible' },
] as const
