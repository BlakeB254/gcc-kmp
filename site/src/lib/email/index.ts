import { Resend } from 'resend'

if (!process.env.RESEND_API_KEY) {
  console.warn('RESEND_API_KEY not configured - emails will be skipped')
}

export const resend = process.env.RESEND_API_KEY
  ? new Resend(process.env.RESEND_API_KEY)
  : null

export const EMAIL_FROM = process.env.EMAIL_FROM || 'GCC <noreply@globalcommunitycleaners.org>'
export const EMAIL_REPLY_TO = process.env.EMAIL_REPLY_TO || 'hello@globalcommunitycleaners.org'

export interface SendEmailOptions {
  to: string | string[]
  subject: string
  html: string
  text?: string
  replyTo?: string
}

export async function sendEmail(options: SendEmailOptions): Promise<{ success: boolean; error?: string }> {
  if (!resend) {
    console.warn('[Email] SKIPPED - No RESEND_API_KEY configured!')
    console.warn('[Email] Would have sent:', options.subject, 'to', options.to)
    // Return false when API key is missing so callers know email wasn't sent
    return { success: false, error: 'Email service not configured (missing RESEND_API_KEY)' }
  }

  try {
    const { data, error } = await resend.emails.send({
      from: EMAIL_FROM,
      to: options.to,
      subject: options.subject,
      html: options.html,
      text: options.text,
      replyTo: options.replyTo || EMAIL_REPLY_TO,
    })

    if (error) {
      console.error('[Email] Failed:', error)
      return { success: false, error: error.message }
    }

    console.log('[Email] Sent:', data?.id, 'to', options.to)
    return { success: true }
  } catch (error) {
    console.error('[Email] Error:', error)
    return {
      success: false,
      error: error instanceof Error ? error.message : 'Unknown error',
    }
  }
}
