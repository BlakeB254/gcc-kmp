import { NextRequest } from 'next/server'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { eq } from 'drizzle-orm'

export const runtime = 'nodejs'

interface RequestBody {
  email?: string
}

/**
 * Check if email already exists for a sponsor.
 * Used during signup step 1 to prevent duplicates before proceeding.
 */
export async function POST(request: NextRequest) {
  try {
    const body: RequestBody = await request.json()
    const { email } = body

    if (!email) {
      return Response.json(
        { message: 'Email required' },
        { status: 400 }
      )
    }

    // Check for existing sponsor with this email
    const existing = await db
      .select({ id: sponsors.id })
      .from(sponsors)
      .where(eq(sponsors.email, email.toLowerCase().trim()))
      .limit(1)

    if (existing.length > 0) {
      return Response.json({
        exists: true,
        field: 'email',
        message: 'This email is already registered. Please log in instead.',
      })
    }

    return Response.json({ exists: false })
  } catch (error) {
    console.error('Check duplicate error:', error)
    return Response.json(
      { message: 'Failed to check for duplicates' },
      { status: 500 }
    )
  }
}
