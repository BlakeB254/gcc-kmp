import { newsletterIssue1Email } from '@/lib/email/templates/newsletter-issue-1'

export const runtime = 'nodejs'

// Renders the newsletter HTML inline so the admin iframe can show a live
// preview. No auth required (preview endpoint only); but it does NOT use
// real recipient data — it always uses placeholder name + a sample
// unsubscribe URL.
export async function GET() {
  const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
  const { html } = newsletterIssue1Email({
    recipientName: 'Daisy',
    unsubscribeUrl: `${appUrl}/unsubscribe?token=preview`,
    appUrl,
    logoUrl: `${appUrl}/images/gcc-logo.png`,
  })
  return new Response(html, {
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
    },
  })
}
