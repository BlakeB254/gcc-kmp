import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'

export const runtime = 'nodejs'

// GET - Test database connection and sponsors table structure
export async function GET() {
  try {
    // Test basic connection
    const connTest = await db.execute(sql`SELECT 1 as test`)

    // Check sponsors table exists and has expected columns - get ALL columns
    const tableCheck = await db.execute(sql`
      SELECT column_name, data_type
      FROM information_schema.columns
      WHERE table_name = 'sponsors'
      ORDER BY ordinal_position
    `)

    // Check specifically for email_verified column
    const emailVerifiedCheck = await db.execute(sql`
      SELECT column_name FROM information_schema.columns
      WHERE table_name = 'sponsors' AND column_name = 'email_verified'
    `)

    // Count sponsors
    const countResult = await db.execute(sql`SELECT count(*)::text as count FROM sponsors`)

    const columns = (tableCheck.rows as any[]).map(r => r.column_name)
    const hasEmailVerified = (emailVerifiedCheck.rows as any[]).length > 0

    return NextResponse.json({
      status: 'ok',
      connection: 'working',
      sponsorCount: (countResult.rows[0] as any)?.count || '0',
      totalColumns: columns.length,
      hasEmailVerified,
      columns,
      missingColumns: !hasEmailVerified ? ['email_verified - MIGRATION NEEDED'] : [],
    })
  } catch (error) {
    console.error('Database diagnostic error:', error)
    return NextResponse.json({
      status: 'error',
      error: error instanceof Error ? error.message : 'Unknown error',
      hint: 'Database connection or schema issue',
    }, { status: 500 })
  }
}

// TEMPORARY DEBUG ENDPOINT - Remove after debugging
export async function POST(request: NextRequest) {
  try {
    const { email } = await request.json()

    if (!email) {
      return NextResponse.json({ error: 'Email required' }, { status: 400 })
    }

    const normalizedEmail = email.toLowerCase().trim()

    // Look up sponsor
    const result = await db.execute(sql`
      SELECT
        id,
        name,
        email,
        email_verified,
        email_verification_expires,
        type,
        hash IS NOT NULL as has_hash,
        salt IS NOT NULL as has_salt,
        LENGTH(hash) as hash_length,
        LENGTH(salt) as salt_length,
        created_at,
        stripe_customer_id
      FROM sponsors
      WHERE email = ${normalizedEmail}
      LIMIT 1
    `)

    const rows = result.rows as any[]

    if (!rows || rows.length === 0) {
      return NextResponse.json({
        found: false,
        message: 'No sponsor found with this email',
        searchedEmail: normalizedEmail,
      })
    }

    const sponsor = rows[0]

    // Check verification expiry
    let verificationStatus = 'unknown'
    if (sponsor.email_verified) {
      verificationStatus = 'verified'
    } else if (sponsor.email_verification_expires) {
      const expires = new Date(sponsor.email_verification_expires)
      if (expires < new Date()) {
        verificationStatus = 'expired'
      } else {
        verificationStatus = 'pending'
      }
    }

    return NextResponse.json({
      found: true,
      sponsor: {
        id: sponsor.id,
        name: sponsor.name,
        email: sponsor.email,
        type: sponsor.type,
        createdAt: sponsor.created_at,
        emailVerified: sponsor.email_verified,
        verificationStatus,
        verificationExpires: sponsor.email_verification_expires,
        hasHash: sponsor.has_hash,
        hasSalt: sponsor.has_salt,
        hashLength: sponsor.hash_length,
        saltLength: sponsor.salt_length,
        hasStripeCustomer: !!sponsor.stripe_customer_id,
      },
      diagnosis: {
        canLogin: sponsor.email_verified && sponsor.has_hash && sponsor.has_salt,
        issues: [
          !sponsor.email_verified && 'Email not verified - must verify before login',
          !sponsor.has_hash && 'No password hash stored - registration may have failed',
          !sponsor.has_salt && 'No password salt stored - registration may have failed',
          sponsor.has_hash && sponsor.hash_length < 100 && 'Hash seems too short - may be corrupted',
          sponsor.has_salt && sponsor.salt_length < 20 && 'Salt seems too short - may be corrupted',
        ].filter(Boolean),
      },
    })
  } catch (error) {
    console.error('Debug endpoint error:', error)
    return NextResponse.json(
      { error: 'Debug failed', details: error instanceof Error ? error.message : 'Unknown' },
      { status: 500 }
    )
  }
}
