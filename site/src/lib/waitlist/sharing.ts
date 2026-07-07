/**
 * Waitlist Sharing Utilities
 *
 * Pre-populated share messages and URL generation for viral growth.
 */
import { appUrl } from '@/lib/env'

export interface ShareData {
  globalRank: number
  referralCode: string
  city: string
  name?: string
}

/**
 * Generate the referral URL
 */
export function getReferralUrl(referralCode: string): string {
  // Resolved lazily (not at module scope) so importing this file never
  // throws at build time when NEXT_PUBLIC_APP_URL is unset.
  return `${appUrl()}/join?ref=${referralCode}`
}

/**
 * Get Twitter/X share message and URL
 */
export function getTwitterShareUrl(data: ShareData): string {
  const message = `I'm supporter #${data.globalRank.toLocaleString()} for @GCCChicago! 🧹

Helping bring paid street cleaning jobs to communities everywhere.

Join the movement: ${getReferralUrl(data.referralCode)}

#CleanStreets #CommunityJobs`

  return `https://twitter.com/intent/tweet?text=${encodeURIComponent(message)}`
}

/**
 * Get Facebook share URL
 */
export function getFacebookShareUrl(data: ShareData): string {
  const url = getReferralUrl(data.referralCode)
  return `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(url)}`
}

/**
 * Get LinkedIn share URL
 */
export function getLinkedInShareUrl(data: ShareData): string {
  const url = getReferralUrl(data.referralCode)
  const title = `I'm supporter #${data.globalRank.toLocaleString()} for Global Community Cleaners!`
  const summary = `This nonprofit is creating $100 cleaning jobs for neighborhood residents - starting in Chicago and expanding everywhere.`

  return `https://www.linkedin.com/shareArticle?mini=true&url=${encodeURIComponent(url)}&title=${encodeURIComponent(title)}&summary=${encodeURIComponent(summary)}`
}

/**
 * Get SMS share message
 */
export function getSmsShareMessage(data: ShareData): string {
  return `I just joined GCC as early supporter #${data.globalRank.toLocaleString()}! They're creating paid jobs for people to clean their own neighborhoods. Sign up and see your rank: ${getReferralUrl(data.referralCode)}`
}

/**
 * Get SMS share URL (for mobile)
 */
export function getSmsShareUrl(data: ShareData): string {
  const message = getSmsShareMessage(data)
  // Use sms: protocol, works on both iOS and Android
  return `sms:?body=${encodeURIComponent(message)}`
}

/**
 * Get email share URL
 */
export function getEmailShareUrl(data: ShareData): string {
  const subject = `Join Global Community Cleaners - I'm supporter #${data.globalRank.toLocaleString()}!`
  const body = `Hey,

I just signed up as an early supporter for Global Community Cleaners (GCC), and I'm #${data.globalRank.toLocaleString()} worldwide!

GCC is creating $100 paid cleaning jobs for neighborhood residents. It started in Chicago's North Lawndale and is expanding everywhere.

Join the movement and see what your rank is: ${getReferralUrl(data.referralCode)}

The earlier you sign up, the higher your rank stays forever. I'm already in - now it's your turn!`

  return `mailto:?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`
}

/**
 * Copy referral link to clipboard
 */
export async function copyReferralLink(referralCode: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(getReferralUrl(referralCode))
    return true
  } catch {
    return false
  }
}

/**
 * Check if Web Share API is available
 */
export function canUseWebShare(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.share
}

/**
 * Use native Web Share API (mobile)
 */
export async function useWebShare(data: ShareData): Promise<boolean> {
  if (!canUseWebShare()) return false

  try {
    await navigator.share({
      title: 'Join Global Community Cleaners',
      text: `I'm supporter #${data.globalRank.toLocaleString()} for GCC! Join the movement:`,
      url: getReferralUrl(data.referralCode),
    })
    return true
  } catch {
    // User cancelled or share failed
    return false
  }
}
