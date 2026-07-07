import { appUrl as resolveAppUrl } from '@/lib/env'

interface WaitlistConfirmationData {
  name?: string
  email: string
  globalRank: number
  cityRank: number
  city: string
  tier: string
  referralCode: string
}

export function waitlistConfirmationEmail(data: WaitlistConfirmationData): { html: string; text: string } {
  const appUrl = resolveAppUrl()
  const referralLink = `${appUrl}/join?ref=${data.referralCode}`
  const profileLink = `${appUrl}/join/success?email=${encodeURIComponent(data.email)}`

  const greeting = data.name ? `Hi ${data.name}` : 'Welcome'

  const tierEmoji: Record<string, string> = {
    founder: '👑',
    pioneer: '🚀',
    early: '⭐',
    supporter: '🌱',
  }

  const emoji = tierEmoji[data.tier.toLowerCase()] || '🌱'

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Welcome to GCC</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #0e3f66 0%, #4a3931 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Global Community Cleaners</h1>
              <p style="margin: 8px 0 0; color: #16763a; font-size: 14px; font-weight: 500;">Clean Streets. Real Money. Your Neighborhood.</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">${greeting}! 🎉</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                You're officially on the GCC waitlist! You're joining a movement to bring paid street cleaning to communities everywhere, starting in North Lawndale, Chicago.
              </p>

              <!-- Rank Card -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background: linear-gradient(135deg, #e7f1eb 0%, #cbe0d3 100%); border-radius: 12px; margin-bottom: 24px;">
                <tr>
                  <td style="padding: 24px; text-align: center;">
                    <p style="margin: 0 0 8px; color: #0b3b1d; font-size: 14px; font-weight: 600; text-transform: uppercase;">Your ${data.tier} Status ${emoji}</p>
                    <p style="margin: 0; color: #1a1a1a; font-size: 48px; font-weight: 700;">#${data.globalRank.toLocaleString()}</p>
                    <p style="margin: 4px 0 0; color: #0e3f66; font-size: 14px;">Global Rank</p>
                  </td>
                </tr>
                <tr>
                  <td style="padding: 0 24px 24px;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                      <tr>
                        <td style="text-align: center; width: 50%;">
                          <p style="margin: 0; color: #1a1a1a; font-size: 24px; font-weight: 700;">#${data.cityRank.toLocaleString()}</p>
                          <p style="margin: 4px 0 0; color: #0e3f66; font-size: 12px;">in ${data.city}</p>
                        </td>
                        <td style="text-align: center; width: 50%;">
                          <p style="margin: 0; color: #1a1a1a; font-size: 16px; font-weight: 600;">${data.referralCode}</p>
                          <p style="margin: 4px 0 0; color: #0e3f66; font-size: 12px;">Your Referral Code</p>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>

              <p style="margin: 0 0 16px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                <strong>Want to climb the ranks?</strong> Share your referral link with friends:
              </p>

              <!-- Referral Link -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="background-color: #f5f5f5; border-radius: 8px; padding: 16px; text-align: center;">
                    <a href="${referralLink}" style="color: #16763a; font-size: 14px; word-break: break-all; text-decoration: none;">${referralLink}</a>
                  </td>
                </tr>
              </table>

              <!-- CTA Button -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 32px;">
                <tr>
                  <td align="center">
                    <a href="${profileLink}" style="display: inline-block; background-color: #16763a; color: #ffffff; font-size: 16px; font-weight: 600; text-decoration: none; padding: 16px 32px; border-radius: 8px;">
                      View Your Profile Card
                    </a>
                  </td>
                </tr>
              </table>

              <p style="margin: 0; color: #6b6b6b; font-size: 14px; line-height: 1.6;">
                We'll notify you when GCC launches in your area. In the meantime, follow us on social media for updates!
              </p>
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background-color: #f9f9f9; padding: 24px 32px; text-align: center; border-top: 1px solid #e5e5e5;">
              <p style="margin: 0 0 8px; color: #6b6b6b; font-size: 12px;">
                Global Community Cleaners | Chicago, IL
              </p>
              <p style="margin: 0; color: #9a9a9a; font-size: 12px;">
                You received this email because you signed up for the GCC waitlist.
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
`

  const text = `
${greeting}! 🎉

You're officially on the GCC waitlist!

YOUR RANK
---------
Global: #${data.globalRank.toLocaleString()}
${data.city}: #${data.cityRank.toLocaleString()}
Status: ${data.tier} ${emoji}
Referral Code: ${data.referralCode}

CLIMB THE RANKS
Share your referral link with friends:
${referralLink}

VIEW YOUR PROFILE
${profileLink}

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}
