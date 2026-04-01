interface SponsorConfirmationData {
  name?: string
  email: string
  amount: number
  blockAddress: string
  isRecurring: boolean
  receiptUrl?: string
}

export function sponsorConfirmationEmail(data: SponsorConfirmationData): { html: string; text: string } {
  const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
  const wallLink = `${appUrl}/sponsor/wall`

  const greeting = data.name ? `Hi ${data.name}` : 'Thank you'

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Thank You for Your Sponsorship</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #16763a 0%, #126431 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Thank You! 💛</h1>
              <p style="margin: 8px 0 0; color: #ffffff; font-size: 14px; font-weight: 500;">Your sponsorship is making a difference</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">${greeting}!</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                Your generosity is putting money directly into the pockets of North Lawndale residents while making streets cleaner and safer for everyone.
              </p>

              <!-- Sponsorship Details -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #e7f1eb; border-radius: 12px; margin-bottom: 24px;">
                <tr>
                  <td style="padding: 24px;">
                    <p style="margin: 0 0 16px; color: #0b3b1d; font-size: 14px; font-weight: 600; text-transform: uppercase;">Sponsorship Details</p>
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                      <tr>
                        <td style="padding: 8px 0; border-bottom: 1px solid #fde047;">
                          <span style="color: #0e3f66; font-size: 14px;">Amount</span>
                        </td>
                        <td style="padding: 8px 0; border-bottom: 1px solid #fde047; text-align: right;">
                          <span style="color: #1a1a1a; font-size: 16px; font-weight: 600;">$${data.amount}${data.isRecurring ? '/month' : ''}</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding: 8px 0; border-bottom: 1px solid #fde047;">
                          <span style="color: #0e3f66; font-size: 14px;">Block</span>
                        </td>
                        <td style="padding: 8px 0; border-bottom: 1px solid #fde047; text-align: right;">
                          <span style="color: #1a1a1a; font-size: 14px; font-weight: 500;">${data.blockAddress}</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding: 8px 0;">
                          <span style="color: #0e3f66; font-size: 14px;">Type</span>
                        </td>
                        <td style="padding: 8px 0; text-align: right;">
                          <span style="color: #1a1a1a; font-size: 14px; font-weight: 500;">${data.isRecurring ? 'Monthly Recurring' : 'One-time'}</span>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>

              <!-- What's Next -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 16px; background-color: #f0fdf4; border-radius: 12px; border-left: 4px solid #22c55e;">
                    <p style="margin: 0 0 12px; color: #166534; font-size: 14px; font-weight: 600;">WHAT HAPPENS NEXT:</p>
                    <ol style="margin: 0; padding-left: 20px; color: #4a4a4a; font-size: 14px; line-height: 1.8;">
                      <li>A local worker will claim your sponsored block</li>
                      <li>They'll clean it within 48 hours</li>
                      <li>You'll receive before/after photos by email</li>
                      <li>100% of your sponsorship goes to the worker!</li>
                    </ol>
                  </td>
                </tr>
              </table>

              <!-- CTA Buttons -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td align="center" style="padding: 8px;">
                    <a href="${wallLink}" style="display: inline-block; background-color: #16763a; color: #ffffff; font-size: 14px; font-weight: 600; text-decoration: none; padding: 12px 24px; border-radius: 8px;">
                      View Sponsor Wall
                    </a>
                  </td>
                  ${data.receiptUrl ? `
                  <td align="center" style="padding: 8px;">
                    <a href="${data.receiptUrl}" style="display: inline-block; background-color: #f5f5f5; color: #4a4a4a; font-size: 14px; font-weight: 600; text-decoration: none; padding: 12px 24px; border-radius: 8px; border: 1px solid #e5e5e5;">
                      View Receipt
                    </a>
                  </td>
                  ` : ''}
                </tr>
              </table>

              <p style="margin: 0; color: #166534; font-size: 14px; line-height: 1.6; background-color: #f0fdf4; padding: 12px; border-radius: 8px; text-align: center;">
                <strong>Tax Deductible:</strong> GCC is a 501(c)(3) nonprofit. Keep this email for your records.
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
                Thank you for supporting clean streets and local jobs.
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
${greeting}!

Thank you for your sponsorship! Your generosity is putting money directly into the pockets of North Lawndale residents.

SPONSORSHIP DETAILS
-------------------
Amount: $${data.amount}${data.isRecurring ? '/month' : ''}
Block: ${data.blockAddress}
Type: ${data.isRecurring ? 'Monthly Recurring' : 'One-time'}

WHAT HAPPENS NEXT:
1. A local worker will claim your sponsored block
2. They'll clean it within 48 hours
3. You'll receive before/after photos by email
4. 100% of your sponsorship goes to the worker!

TAX DEDUCTIBLE: GCC is a 501(c)(3) nonprofit. Keep this email for your records.

View Sponsor Wall: ${wallLink}
${data.receiptUrl ? `View Receipt: ${data.receiptUrl}` : ''}

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}
