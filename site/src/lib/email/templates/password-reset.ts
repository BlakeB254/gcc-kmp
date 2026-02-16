interface PasswordResetData {
  name: string
  resetUrl: string
}

export function passwordResetEmail(data: PasswordResetData): { html: string; text: string } {
  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Reset Your Password - GCC</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #d4a017 0%, #b8860b 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Reset Your Password</h1>
              <p style="margin: 8px 0 0; color: #fff8dc; font-size: 14px; font-weight: 500;">Let's get you back in!</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">Hi ${data.name}!</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                We received a request to reset your password. Click the button below to create a new password.
              </p>

              <!-- Reset Button -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td align="center">
                    <a href="${data.resetUrl}" style="display: inline-block; padding: 16px 48px; background: linear-gradient(135deg, #166534 0%, #14532d 100%); color: #ffffff; text-decoration: none; font-size: 18px; font-weight: 600; border-radius: 12px; box-shadow: 0 4px 12px rgba(22, 101, 52, 0.3);">
                      Reset My Password
                    </a>
                  </td>
                </tr>
              </table>

              <!-- Security notice -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 20px; background-color: #fef9c3; border-radius: 12px; border-left: 4px solid #d4a017;">
                    <p style="margin: 0; color: #6b5344; font-size: 14px; line-height: 1.6;">
                      <strong>Security Notice:</strong> This link expires in <strong>1 hour</strong>. If you didn't request a password reset, you can safely ignore this email - your password will remain unchanged.
                    </p>
                  </td>
                </tr>
              </table>

              <p style="margin: 0 0 16px; color: #6b6b6b; font-size: 14px; line-height: 1.6;">
                If the button doesn't work, copy and paste this link into your browser:
              </p>
              <p style="margin: 0; color: #166534; font-size: 12px; word-break: break-all; line-height: 1.4;">
                ${data.resetUrl}
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
                You received this email because a password reset was requested for your account.
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

We received a request to reset your password. Click the link below to create a new password:

${data.resetUrl}

This link expires in 1 hour.

If you didn't request a password reset, you can safely ignore this email - your password will remain unchanged.

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}
