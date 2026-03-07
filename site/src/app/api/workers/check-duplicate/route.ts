import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq, or, and } from 'drizzle-orm'
import { apiSuccess, apiError, withErrorHandler } from '@/lib/api-helpers'

export const runtime = 'nodejs'

interface RequestBody {
  email?: string
  phone?: string
}

/**
 * Check if email or phone already exists for a verified worker.
 * Used during signup step 1 to prevent duplicates before proceeding.
 */
export const POST = withErrorHandler(async (request: Request) => {
  const body: RequestBody = await request.json()
  const { email, phone } = body

  if (!email && !phone) {
    return apiError('Email or phone required', 400)
  }

  // Normalize phone to just digits for comparison
  const normalizedPhone = phone?.replace(/\D/g, '')

  // Build conditions for checking duplicates
  const conditions = []

  if (email) {
    conditions.push(eq(workers.email, email.toLowerCase().trim()))
  }

  if (normalizedPhone && normalizedPhone.length >= 10) {
    // Check phone with just the last 10 digits (strip country code if present)
    const phoneDigits = normalizedPhone.slice(-10)
    conditions.push(eq(workers.phone, `(${phoneDigits.slice(0, 3)}) ${phoneDigits.slice(3, 6)}-${phoneDigits.slice(6)}`))
  }

  if (conditions.length === 0) {
    return apiSuccess({ exists: false })
  }

  // Check for existing verified workers
  const existing = await db
    .select({
      id: workers.id,
      email: workers.email,
      phone: workers.phone,
      emailVerified: workers.emailVerified,
    })
    .from(workers)
    .where(
      and(
        or(...conditions),
        eq(workers.emailVerified, true) // Only check verified accounts
      )
    )
    .limit(1)

  if (existing.length > 0) {
    const match = existing[0]
    const emailMatch = email && match.email.toLowerCase() === email.toLowerCase().trim()

    return apiSuccess({
      exists: true,
      field: emailMatch ? 'email' : 'phone',
      message: emailMatch
        ? 'This email is already registered. Please log in instead.'
        : 'This phone number is already registered. Please log in instead.',
    })
  }

  return apiSuccess({ exists: false })
})
