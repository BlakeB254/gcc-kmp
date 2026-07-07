import { newsletterIssue1Email } from '@/lib/email/templates/newsletter-issue-1'
import { appUrl as resolveAppUrl } from '@/lib/env'

export const runtime = 'nodejs'
// Must stay request-time: a parameterless GET is otherwise eligible for
// build-time static evaluation, where appUrl()'s production fail-fast would
// break `next build` on machines without NEXT_PUBLIC_APP_URL set.
export const dynamic = 'force-dynamic'

// Renders the newsletter HTML inline so the admin iframe can show a live
// preview. No auth required (preview endpoint only); but it does NOT use
// real recipient data — it always uses placeholder name + a sample
// unsubscribe URL.
export async function GET() {
  const appUrl = resolveAppUrl()
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
