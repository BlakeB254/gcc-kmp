interface SecurityAlertData {
  name: string
  /** Short past-tense description, e.g. "Your password was changed". */
  headline: string
  /** Optional extra context line, e.g. the new email address. */
  detail?: string
}

/**
 * Generic account-security notification, sent after password/email/phone
 * changes and 2FA enable/disable. Deliberately action-free: if the user
 * made the change there is nothing to do, and if they didn't, the copy
 * points them at support.
 */
export function securityAlertEmail(data: SecurityAlertData): { html: string; text: string } {
  const detailHtml = data.detail
    ? `<p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">${data.detail}</p>`
    : ''

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Security Alert - GCC</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <tr>
            <td style="background: linear-gradient(135deg, #16763a 0%, #126431 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Security Alert</h1>
              <p style="margin: 8px 0 0; color: #fff8dc; font-size: 14px; font-weight: 500;">A change was made to your GCC account</p>
            </td>
          </tr>
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">Hi ${data.name},</h2>
              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                ${data.headline}
              </p>
              ${detailHtml}
              <p style="margin: 0 0 8px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                If you made this change, you can ignore this email.
              </p>
              <p style="margin: 0; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                If you did <strong>not</strong> make this change, reply to this email right away so we can help secure your account.
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
    `Hi ${data.name},`,
    '',
    data.headline,
    ...(data.detail ? ['', data.detail] : []),
    '',
    'If you made this change, you can ignore this email.',
    'If you did NOT make this change, reply to this email right away so we can help secure your account.',
    '',
    'Global Community Cleaners',
  ].join('\n')

  return { html, text }
}
