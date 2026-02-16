interface DonationConfirmationData {
  name?: string
  email: string
  amount: number
  isRecurring: boolean
  receiptUrl?: string
}

export function donationConfirmationEmail(data: DonationConfirmationData): { html: string; text: string } {
  const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
  const wallLink = `${appUrl}/support/wall`

  const greeting = data.name ? `Hi ${data.name}` : 'Thank you'
  const blocksEquivalent = Math.floor(data.amount / 100)

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Thank You for Your Donation</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Thank You!</h1>
              <p style="margin: 8px 0 0; color: #ffffff; font-size: 14px; font-weight: 500;">Your donation is making a difference</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">${greeting}!</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                Your generosity goes directly into the GCC General Fund, where we allocate it to blocks that need cleaning the most. Every dollar puts money in workers' pockets while making North Lawndale cleaner.
              </p>

              <!-- Donation Details -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f0fdf4; border-radius: 12px; margin-bottom: 24px;">
                <tr>
                  <td style="padding: 24px;">
                    <p style="margin: 0 0 16px; color: #166534; font-size: 14px; font-weight: 600; text-transform: uppercase;">Donation Details</p>
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                      <tr>
                        <td style="padding: 8px 0; border-bottom: 1px solid #bbf7d0;">
                          <span style="color: #166534; font-size: 14px;">Amount</span>
                        </td>
                        <td style="padding: 8px 0; border-bottom: 1px solid #bbf7d0; text-align: right;">
                          <span style="color: #1a1a1a; font-size: 16px; font-weight: 600;">$${data.amount}${data.isRecurring ? '/month' : ''}</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding: 8px 0; border-bottom: 1px solid #bbf7d0;">
                          <span style="color: #166534; font-size: 14px;">Allocation</span>
                        </td>
                        <td style="padding: 8px 0; border-bottom: 1px solid #bbf7d0; text-align: right;">
                          <span style="color: #1a1a1a; font-size: 14px; font-weight: 500;">General Fund</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding: 8px 0;">
                          <span style="color: #166534; font-size: 14px;">Impact</span>
                        </td>
                        <td style="padding: 8px 0; text-align: right;">
                          <span style="color: #1a1a1a; font-size: 14px; font-weight: 500;">${blocksEquivalent > 0 ? `${blocksEquivalent} block${blocksEquivalent > 1 ? 's' : ''} can be funded` : 'Contributes to block funding'}</span>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>

              <!-- How It Works -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 16px; background-color: #fefce8; border-radius: 12px; border-left: 4px solid #eab308;">
                    <p style="margin: 0 0 12px; color: #854d0e; font-size: 14px; font-weight: 600;">HOW YOUR DONATION HELPS:</p>
                    <ol style="margin: 0; padding-left: 20px; color: #4a4a4a; font-size: 14px; line-height: 1.8;">
                      <li>Your donation enters the GCC General Fund</li>
                      <li>GCC allocates funds to priority blocks</li>
                      <li>Local workers clean sponsored blocks</li>
                      <li>100% of block funding goes to workers!</li>
                    </ol>
                  </td>
                </tr>
              </table>

              <!-- Sponsor Account Info -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 16px; background-color: #eff6ff; border-radius: 12px; border-left: 4px solid #3b82f6;">
                    <p style="margin: 0 0 8px; color: #1e40af; font-size: 14px; font-weight: 600;">YOUR SPONSOR ACCOUNT</p>
                    <p style="margin: 0 0 12px; color: #4a4a4a; font-size: 14px; line-height: 1.6;">
                      A sponsor account has been created for <strong>${data.email}</strong>. Log in anytime to view your donation history, receipts, and impact.
                    </p>
                    <a href="${appUrl}/sponsor/login" style="display: inline-block; background-color: #3b82f6; color: #ffffff; font-size: 14px; font-weight: 600; text-decoration: none; padding: 10px 20px; border-radius: 8px;">
                      Access Your Account
                    </a>
                  </td>
                </tr>
              </table>

              <!-- CTA Buttons -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td align="center" style="padding: 8px;">
                    <a href="${wallLink}" style="display: inline-block; background-color: #22c55e; color: #ffffff; font-size: 14px; font-weight: 600; text-decoration: none; padding: 12px 24px; border-radius: 8px;">
                      View Support Wall
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

Thank you for your donation! Your generosity goes directly into the GCC General Fund, where we allocate it to blocks that need cleaning the most.

DONATION DETAILS
----------------
Amount: $${data.amount}${data.isRecurring ? '/month' : ''}
Allocation: General Fund
Impact: ${blocksEquivalent > 0 ? `${blocksEquivalent} block${blocksEquivalent > 1 ? 's' : ''} can be funded` : 'Contributes to block funding'}

HOW YOUR DONATION HELPS:
1. Your donation enters the GCC General Fund
2. GCC allocates funds to priority blocks
3. Local workers clean sponsored blocks
4. 100% of block funding goes to workers!

YOUR SPONSOR ACCOUNT
--------------------
A sponsor account has been created for ${data.email}.
Log in anytime to view your donation history, receipts, and impact.
Access Your Account: ${appUrl}/sponsor/login

TAX DEDUCTIBLE: GCC is a 501(c)(3) nonprofit. Keep this email for your records.

View Support Wall: ${wallLink}
${data.receiptUrl ? `View Receipt: ${data.receiptUrl}` : ''}

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}
