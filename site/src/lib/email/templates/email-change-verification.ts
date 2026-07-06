interface EmailChangeVerificationData {
  name: string
  code: string
}

/**
 * Sent to the NEW address during an email change. The account email only
 * switches once this code is entered in the app, proving the user controls
 * the new inbox.
 */
export function emailChangeVerificationEmail(
  data: EmailChangeVerificationData,
): { html: string; text: string } {
  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Confirm Your New Email - GCC</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <tr>
            <td style="background: linear-gradient(135deg, #16763a 0%, #126431 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Confirm Your New Email</h1>
              <p style="margin: 8px 0 0; color: #fff8dc; font-size: 14px; font-weight: 500;">Enter this code in the GCC app</p>
            </td>
          </tr>
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">Hi ${data.name}!</h2>
              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                You asked to change the email address on your GCC account to this one.
                Enter the code below in the app to confirm. The code expires in 30 minutes.
              </p>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td align="center">
                    <div style="display: inline-block; padding: 16px 32px; background-color: #f0f7f2; border: 2px dashed #16763a; border-radius: 12px;">
                      <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #126431;">${data.code}</span>
                    </div>
                  </td>
                </tr>
              </table>
              <p style="margin: 0; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                If you didn't request this change, you can ignore this email — your account email will not change.
              </p>
            </td>
          </tr>
          <tr>
            <td style="padding: 24px 32px; background-color: #fafafa; text-align: center;">
              <p style="margin: 0; color: #9a9a9a; font-size: 13px;">Global Community Cleaners</p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`

  const text = [
    `Hi ${data.name}!`,
    '',
    'You asked to change the email address on your GCC account to this one.',
    'Enter this code in the app to confirm (expires in 30 minutes):',
    '',
    `    ${data.code}`,
    '',
    "If you didn't request this change, you can ignore this email — your account email will not change.",
    '',
    'Global Community Cleaners',
  ].join('\n')

  return { html, text }
}
