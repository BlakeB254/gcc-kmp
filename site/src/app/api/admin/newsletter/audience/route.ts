import { NextResponse } from 'next/server'
import { requireAdmin } from '@/lib/admin-auth'
import { getNewsletterAudience } from '@/lib/newsletter-audience'

export const runtime = 'nodejs'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const audience = await getNewsletterAudience()
  return NextResponse.json({
    data: audience,
    counts: {
      total: audience.length,
      sponsors: audience.filter((a) => a.source === 'sponsor').length,
      waitlist: audience.filter((a) => a.source === 'waitlist').length,
    },
  })
}
