interface WorkerSignupData {
  name: string
  email: string
}

export function workerSignupEmail(data: WorkerSignupData): { html: string; text: string } {
  const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
  const dashboardLink = `${appUrl}/worker/dashboard`

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Welcome to GCC Workers</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #166534 0%, #14532d 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Welcome to GCC! 🧹</h1>
              <p style="margin: 8px 0 0; color: #86efac; font-size: 14px; font-weight: 500;">Your application is being reviewed</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">Hi ${data.name}!</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                Thanks for applying to become a GCC cleaner! We're excited to have you join our community of North Lawndale workers earning real money while making their neighborhood shine.
              </p>

              <!-- Steps -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 16px; background-color: #f0fdf4; border-radius: 12px; border-left: 4px solid #22c55e;">
                    <p style="margin: 0 0 12px; color: #166534; font-size: 14px; font-weight: 600;">WHAT HAPPENS NEXT:</p>
                    <ol style="margin: 0; padding-left: 20px; color: #4a4a4a; font-size: 14px; line-height: 1.8;">
                      <li>We'll review your application (usually within 24-48 hours)</li>
                      <li>You'll receive an approval email with next steps</li>
                      <li>Complete a brief orientation on the app</li>
                      <li>Start claiming blocks and earning $100 per block!</li>
                    </ol>
                  </td>
                </tr>
              </table>

              <!-- Stats -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 32px;">
                <tr>
                  <td style="text-align: center; padding: 16px; background-color: #fef9c3; border-radius: 8px; width: 33%;">
                    <p style="margin: 0; color: #1a1a1a; font-size: 24px; font-weight: 700;">$100</p>
                    <p style="margin: 4px 0 0; color: #6b5344; font-size: 12px;">Per Block</p>
                  </td>
                  <td style="text-align: center; padding: 16px; background-color: #dcfce7; border-radius: 8px; width: 33%;">
                    <p style="margin: 0; color: #1a1a1a; font-size: 24px; font-weight: 700;">3-4h</p>
                    <p style="margin: 4px 0 0; color: #166534; font-size: 12px;">Avg Time</p>
                  </td>
                  <td style="text-align: center; padding: 16px; background-color: #e0f2fe; border-radius: 8px; width: 33%;">
                    <p style="margin: 0; color: #1a1a1a; font-size: 24px; font-weight: 700;">Instant</p>
                    <p style="margin: 4px 0 0; color: #0369a1; font-size: 12px;">Payout</p>
                  </td>
                </tr>
              </table>

              <p style="margin: 0; color: #6b6b6b; font-size: 14px; line-height: 1.6;">
                Questions? Reply to this email and we'll get back to you.
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
                You received this email because you applied to become a GCC worker.
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
Hi ${data.name}!

Thanks for applying to become a GCC cleaner!

WHAT HAPPENS NEXT:
1. We'll review your application (usually within 24-48 hours)
2. You'll receive an approval email with next steps
3. Complete a brief orientation on the app
4. Start claiming blocks and earning $100 per block!

EARNINGS
- $100 per block
- 3-4 hours average
- Instant payout

Questions? Reply to this email and we'll get back to you.

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}
