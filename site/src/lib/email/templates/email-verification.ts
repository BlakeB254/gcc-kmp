interface EmailVerificationData {
  name: string
  verificationUrl: string
  verificationCode: string
}

export function emailVerificationEmail(data: EmailVerificationData): { html: string; text: string } {
  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Verify Your Email - GCC</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #d4a017 0%, #b8860b 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Verify Your Email</h1>
              <p style="margin: 8px 0 0; color: #fff8dc; font-size: 14px; font-weight: 500;">One more step to join GCC!</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">Hi ${data.name}!</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                Thanks for signing up to become a GCC worker! Please verify your email address to continue with your application.
              </p>

              <!-- Verification Button -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td align="center">
                    <a href="${data.verificationUrl}" style="display: inline-block; padding: 16px 48px; background: linear-gradient(135deg, #166534 0%, #14532d 100%); color: #ffffff; text-decoration: none; font-size: 18px; font-weight: 600; border-radius: 12px; box-shadow: 0 4px 12px rgba(22, 101, 52, 0.3);">
                      Verify My Email
                    </a>
                  </td>
                </tr>
              </table>

              <!-- Or use code -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 20px; background-color: #f9f9f9; border-radius: 12px; text-align: center;">
                    <p style="margin: 0 0 12px; color: #6b6b6b; font-size: 14px;">
                      Or enter this code on the verification page:
                    </p>
                    <p style="margin: 0; font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #166534; font-family: monospace;">
                      ${data.verificationCode}
                    </p>
                  </td>
                </tr>
              </table>

              <p style="margin: 0 0 16px; color: #4a4a4a; font-size: 14px; line-height: 1.6;">
                This verification link expires in <strong>24 hours</strong>. If you didn't sign up for GCC, you can safely ignore this email.
              </p>

              <p style="margin: 0; color: #6b6b6b; font-size: 14px; line-height: 1.6;">
                Questions? Reply to this email and we'll help you out.
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
                You received this email because you signed up to become a GCC worker.
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

Thanks for signing up to become a GCC worker! Please verify your email address to continue with your application.

Click here to verify: ${data.verificationUrl}

Or use this code: ${data.verificationCode}

This verification link expires in 24 hours.

If you didn't sign up for GCC, you can safely ignore this email.

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}
