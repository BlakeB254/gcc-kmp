/**
 * Sponsor cookie-session auth. Sibling of admin-auth.ts / mobile-auth.ts.
 *
 * Encapsulates the previously copy-pasted block: read the `sponsor-token`
 * cookie, verify the JWT, require `type === 'sponsor'`, and confirm the
 * sponsor row still exists.
 */
import { NextRequest } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { verifyToken } from '@/lib/auth'

export interface SponsorAuth {
  id: number
  email: string
  name: string
}

/**
 * Authenticate a sponsor from the `sponsor-token` cookie.
 * Returns the sponsor's { id, email, name } or null if not authenticated.
 *
 * Usage: `const sponsor = await requireSponsor(request); if (!sponsor) return 401...`
 */
export async function requireSponsor(request: NextRequest): Promise<SponsorAuth | null> {
  const token = request.cookies.get('sponsor-token')?.value
  if (!token) return null

  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'sponsor') return null

  const [sponsor] = await db
    .select({ id: sponsors.id, email: sponsors.email, name: sponsors.name })
    .from(sponsors)
    .where(eq(sponsors.id, payload.id))
    .limit(1)

  return sponsor ?? null
}
