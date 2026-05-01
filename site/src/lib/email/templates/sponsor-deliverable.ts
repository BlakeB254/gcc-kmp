import { formatDollars } from '@/lib/sponsor-deliverables'

const BRAND_GREEN = '#16763a'
const BRAND_GREEN_DARK = '#0b3b1d'

interface ShellOpts {
  title: string
  preheader: string
  bodyHtml: string
}

function shell({ title, preheader, bodyHtml }: ShellOpts): string {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${title}</title>
</head>
<body style="margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;background-color:#f5f5f5;">
<div style="display:none;max-height:0;overflow:hidden;">${preheader}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f5f5f5;padding:32px 16px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,0.08);">
<tr><td style="background:linear-gradient(135deg,${BRAND_GREEN} 0%,${BRAND_GREEN_DARK} 100%);padding:28px 32px;">
  <div style="color:#ffffff;font-size:13px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;opacity:0.85;">Global Community Cleaners</div>
  <h1 style="margin:8px 0 0;color:#ffffff;font-size:24px;font-weight:700;">${title}</h1>
</td></tr>
<tr><td style="padding:32px;">${bodyHtml}</td></tr>
<tr><td style="background-color:#f9f9f9;padding:20px 32px;text-align:center;border-top:1px solid #e5e5e5;">
  <p style="margin:0 0 4px;color:#6b6b6b;font-size:12px;">Global Community Cleaners · Chicago, IL</p>
  <p style="margin:0;color:#9a9a9a;font-size:12px;">501(c)(3) nonprofit · EIN ${process.env.GCC_EIN || '00-0000000'}</p>
</td></tr>
</table>
</td></tr>
</table>
</body>
</html>`
}

// ---------------------------------------------------------------------------
// SPONSOR-FACING TEMPLATES (one per deliverable kind)
// ---------------------------------------------------------------------------

interface SponsorBaseData {
  sponsorName: string
  amountCents: number
  giftDate: string
  externalRef?: string | null
}

export function recognitionWallEmail(d: SponsorBaseData & { wallUrl: string }) {
  const html = shell({
    title: `You're on the wall, ${d.sponsorName}`,
    preheader: `${d.sponsorName} is now featured on the sponsor recognition wall.`,
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">Thank you, ${d.sponsorName}.</p>
      <p style="margin:0 0 20px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        Your ${formatDollars(d.amountCents)} sponsorship has earned a permanent spot on the
        Global Community Cleaners <strong>sponsor recognition wall</strong>.
      </p>
      <p style="margin:0 0 24px;text-align:center;">
        <a href="${d.wallUrl}" style="display:inline-block;background-color:${BRAND_GREEN};color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;padding:12px 24px;border-radius:8px;">View the Wall</a>
      </p>
      <p style="margin:0;color:#6b6b6b;font-size:13px;line-height:1.6;">
        Want to update how your name appears? Reply to this email and we'll adjust it.
      </p>
    `,
  })
  return { html, text: `Thank you, ${d.sponsorName}. Your ${formatDollars(d.amountCents)} sponsorship is on the recognition wall: ${d.wallUrl}` }
}

export function certificateEmail(d: SponsorBaseData & { certificateUrl: string }) {
  const html = shell({
    title: 'Your certificate of appreciation',
    preheader: 'A digital certificate honoring your sponsorship.',
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">${d.sponsorName},</p>
      <p style="margin:0 0 20px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        Please accept this digital certificate of appreciation for your generous
        <strong>${formatDollars(d.amountCents)}</strong> sponsorship of Global Community Cleaners.
      </p>
      <p style="margin:0 0 24px;text-align:center;">
        <a href="${d.certificateUrl}" style="display:inline-block;background-color:${BRAND_GREEN};color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;padding:12px 24px;border-radius:8px;">View Certificate</a>
      </p>
      <p style="margin:0;color:#6b6b6b;font-size:13px;line-height:1.6;">
        The certificate is print-ready (Letter, landscape). Use your browser's print menu and select "Save as PDF" if you'd like a copy for your records.
      </p>
    `,
  })
  return { html, text: `${d.sponsorName}, your digital certificate of appreciation is ready: ${d.certificateUrl}` }
}

export function taxReceiptEmail(
  d: SponsorBaseData & { receiptNumber: string; receiptUrl: string }
) {
  const html = shell({
    title: 'Tax-deductible donation receipt',
    preheader: `Receipt ${d.receiptNumber} — for your records.`,
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">${d.sponsorName},</p>
      <p style="margin:0 0 16px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        Attached below is your tax-deductible donation receipt for your <strong>${formatDollars(d.amountCents)}</strong>
        contribution to Global Community Cleaners, a 501(c)(3) nonprofit.
      </p>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f0fdf4;border-left:4px solid #22c55e;border-radius:8px;margin:16px 0 24px;">
        <tr><td style="padding:16px 20px;">
          <p style="margin:0 0 6px;color:#166534;font-size:12px;font-weight:600;text-transform:uppercase;letter-spacing:0.06em;">Receipt Details</p>
          <p style="margin:0;color:#1a1a1a;font-size:14px;line-height:1.7;">
            <strong>Receipt #:</strong> ${d.receiptNumber}<br>
            <strong>Amount:</strong> ${formatDollars(d.amountCents)}<br>
            <strong>Date:</strong> ${new Date(d.giftDate).toLocaleDateString('en-US', { dateStyle: 'long' })}<br>
            ${d.externalRef ? `<strong>Reference:</strong> ${d.externalRef}<br>` : ''}
            <strong>EIN:</strong> ${process.env.GCC_EIN || '00-0000000'}
          </p>
        </td></tr>
      </table>
      <p style="margin:0 0 24px;text-align:center;">
        <a href="${d.receiptUrl}" style="display:inline-block;background-color:${BRAND_GREEN};color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;padding:12px 24px;border-radius:8px;">View / Print Receipt</a>
      </p>
      <p style="margin:0;color:#6b6b6b;font-size:13px;line-height:1.6;">
        No goods or services were provided in exchange for this contribution. Please consult your tax advisor for deduction guidance.
      </p>
    `,
  })
  return { html, text: `${d.sponsorName}, your tax receipt #${d.receiptNumber} for ${formatDollars(d.amountCents)} is ready: ${d.receiptUrl}` }
}

export function newsletterFeatureEmail(d: SponsorBaseData) {
  const html = shell({
    title: "You'll be in our next newsletter",
    preheader: 'A feature blurb is queued for the next monthly newsletter.',
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">${d.sponsorName},</p>
      <p style="margin:0 0 16px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        We've queued a feature blurb honoring your <strong>${formatDollars(d.amountCents)}</strong> sponsorship
        for our next monthly newsletter.
      </p>
      <p style="margin:0 0 16px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        We'll send you a preview before it goes out so you can approve the wording (and let us know if you'd
        rather stay anonymous in newsletter copy).
      </p>
    `,
  })
  return { html, text: `${d.sponsorName}, your feature blurb is queued for the next GCC monthly newsletter — we'll send a preview before it goes out.` }
}

export function socialRecognitionEmail(d: SponsorBaseData) {
  const html = shell({
    title: 'Social media recognition coming up',
    preheader: 'A social-media shoutout is being prepared for your sponsorship.',
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">${d.sponsorName},</p>
      <p style="margin:0 0 16px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        We're preparing a social-media post recognizing your <strong>${formatDollars(d.amountCents)}</strong>
        sponsorship across X, LinkedIn, and Instagram.
      </p>
      <p style="margin:0 0 16px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        Want us to tag a specific account or include particular language? Reply to this email and we'll
        adjust before publishing.
      </p>
    `,
  })
  return { html, text: `${d.sponsorName}, GCC is drafting a social-media shoutout for your ${formatDollars(d.amountCents)} sponsorship across X, LinkedIn, and Instagram.` }
}

// ---------------------------------------------------------------------------
// ADMIN-FACING NOTIFICATIONS
// ---------------------------------------------------------------------------

export function adminPackageCreatedEmail(d: {
  sponsorName: string
  sponsorEmail: string
  amountCents: number
  source: 'online' | 'offline'
  externalRef?: string | null
  adminUrl: string
}) {
  const sourceLabel = d.source === 'online' ? 'Online (Stripe)' : 'Offline (manual entry)'
  const html = shell({
    title: 'New sponsor deliverable package',
    preheader: `${d.sponsorName} · ${formatDollars(d.amountCents)} · ${sourceLabel}`,
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">
        A new <strong>${formatDollars(d.amountCents)}</strong> sponsorship package has been created and is awaiting deliverable triggers.
      </p>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f9fafb;border-radius:8px;margin:0 0 20px;">
        <tr><td style="padding:16px 20px;">
          <p style="margin:0;color:#1a1a1a;font-size:14px;line-height:1.8;">
            <strong>Sponsor:</strong> ${d.sponsorName}<br>
            <strong>Email:</strong> ${d.sponsorEmail}<br>
            <strong>Amount:</strong> ${formatDollars(d.amountCents)}<br>
            <strong>Source:</strong> ${sourceLabel}<br>
            ${d.externalRef ? `<strong>Reference:</strong> ${d.externalRef}<br>` : ''}
          </p>
        </td></tr>
      </table>
      <p style="margin:0 0 24px;">
        <a href="${d.adminUrl}" style="display:inline-block;background-color:${BRAND_GREEN};color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;padding:12px 24px;border-radius:8px;">Open in Admin</a>
      </p>
      <p style="margin:0;color:#6b6b6b;font-size:13px;line-height:1.6;">
        Five deliverables are now pending: recognition wall, certificate, tax receipt, newsletter feature, and social recognition.
      </p>
    `,
  })
  const text = `New sponsor deliverable package: ${d.sponsorName} (${d.sponsorEmail}) — ${formatDollars(d.amountCents)} via ${sourceLabel}. Open: ${d.adminUrl}`
  return { html, text }
}

export function adminItemTriggeredEmail(d: {
  sponsorName: string
  sponsorEmail: string
  itemLabel: string
  amountCents: number
  artifactUrl?: string
  adminUrl: string
}) {
  const html = shell({
    title: `Deliverable triggered: ${d.itemLabel}`,
    preheader: `${d.sponsorName} · ${d.itemLabel}`,
    bodyHtml: `
      <p style="margin:0 0 16px;color:#1a1a1a;font-size:16px;line-height:1.6;">
        <strong>${d.itemLabel}</strong> has been triggered for <strong>${d.sponsorName}</strong> (${formatDollars(d.amountCents)} sponsor).
      </p>
      <p style="margin:0 0 16px;color:#4a4a4a;font-size:15px;line-height:1.6;">
        Notification sent to: <a href="mailto:${d.sponsorEmail}">${d.sponsorEmail}</a>
      </p>
      ${
        d.artifactUrl
          ? `<p style="margin:0 0 24px;"><a href="${d.artifactUrl}" style="display:inline-block;background-color:${BRAND_GREEN};color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;padding:12px 24px;border-radius:8px;">View Artifact</a></p>`
          : ''
      }
      <p style="margin:0;">
        <a href="${d.adminUrl}" style="color:${BRAND_GREEN};font-size:14px;text-decoration:none;">Open package in admin →</a>
      </p>
    `,
  })
  const text = `Deliverable triggered: ${d.itemLabel} for ${d.sponsorName} (${d.sponsorEmail}). Admin: ${d.adminUrl}${d.artifactUrl ? ` · Artifact: ${d.artifactUrl}` : ''}`
  return { html, text }
}
