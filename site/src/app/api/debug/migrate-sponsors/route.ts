import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'

export const runtime = 'nodejs'

// POST - Run migrations to add missing columns to sponsors table
// TEMPORARY - Remove after migrations are applied
export async function POST(request: NextRequest) {
  const results: string[] = []
  const errors: string[] = []

  try {
    // Check which columns exist
    const columnsResult = await db.execute(sql`
      SELECT column_name FROM information_schema.columns
      WHERE table_name = 'sponsors'
    `)
    const existingColumns = new Set((columnsResult.rows as any[]).map(r => r.column_name))

    // List of columns that should exist with their definitions
    const requiredColumns = [
      { name: 'email_verified', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS email_verified BOOLEAN DEFAULT false` },
      { name: 'email_verification_token', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS email_verification_token VARCHAR` },
      { name: 'email_verification_expires', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS email_verification_expires TIMESTAMPTZ` },
      { name: 'email_verified_at', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ` },
      { name: 'fund_allocation', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS fund_allocation VARCHAR DEFAULT 'general_fund'` },
      { name: 'preferred_community', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS preferred_community VARCHAR` },
      { name: 'wait_for_community_launch', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS wait_for_community_launch BOOLEAN DEFAULT false` },
      { name: 'let_gcc_choose_blocks', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS let_gcc_choose_blocks BOOLEAN DEFAULT true` },
      { name: 'signup_timestamp', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS signup_timestamp TIMESTAMPTZ` },
      { name: 'signup_rankings_global', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS signup_rankings_global NUMERIC` },
      { name: 'referral_code', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS referral_code VARCHAR UNIQUE` },
      { name: 'referred_by', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS referred_by VARCHAR` },
      { name: 'active_sponsorship', sql: `ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS active_sponsorship BOOLEAN DEFAULT false` },
    ]

    // Check for early_adopter_tier enum type
    const enumCheck = await db.execute(sql`
      SELECT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'enum_workers_early_adopter_tier') as exists
    `)
    const enumExists = (enumCheck.rows[0] as any)?.exists

    if (!enumExists) {
      try {
        await db.execute(sql`
          CREATE TYPE enum_workers_early_adopter_tier AS ENUM ('founder', 'pioneer', 'early', 'standard')
        `)
        results.push('Created enum: enum_workers_early_adopter_tier')
      } catch (e) {
        // Enum might already exist
        errors.push(`Enum creation: ${(e as Error).message}`)
      }
    }

    // Add early_adopter_tier column if missing
    if (!existingColumns.has('early_adopter_tier')) {
      try {
        await db.execute(sql`
          ALTER TABLE sponsors ADD COLUMN IF NOT EXISTS early_adopter_tier enum_workers_early_adopter_tier DEFAULT 'standard'
        `)
        results.push('Added column: early_adopter_tier')
      } catch (e) {
        errors.push(`early_adopter_tier: ${(e as Error).message}`)
      }
    }

    // Run migrations for each missing column
    for (const col of requiredColumns) {
      if (!existingColumns.has(col.name)) {
        try {
          await db.execute(sql.raw(col.sql))
          results.push(`Added column: ${col.name}`)
        } catch (e) {
          errors.push(`${col.name}: ${(e as Error).message}`)
        }
      } else {
        results.push(`Column exists: ${col.name}`)
      }
    }

    // Verify final state
    const finalColumnsResult = await db.execute(sql`
      SELECT column_name FROM information_schema.columns
      WHERE table_name = 'sponsors'
      ORDER BY ordinal_position
    `)
    const finalColumns = (finalColumnsResult.rows as any[]).map(r => r.column_name)

    return NextResponse.json({
      success: true,
      results,
      errors: errors.length > 0 ? errors : undefined,
      finalColumnCount: finalColumns.length,
      finalColumns,
    })
  } catch (error) {
    console.error('Migration error:', error)
    return NextResponse.json({
      success: false,
      error: error instanceof Error ? error.message : 'Unknown error',
      results,
      errors,
    }, { status: 500 })
  }
}

// GET - Check migration status
export async function GET() {
  try {
    const columnsResult = await db.execute(sql`
      SELECT column_name FROM information_schema.columns
      WHERE table_name = 'sponsors'
      ORDER BY ordinal_position
    `)
    const columns = (columnsResult.rows as any[]).map(r => r.column_name)

    const requiredColumns = [
      'email_verified',
      'email_verification_token',
      'email_verification_expires',
      'email_verified_at',
      'early_adopter_tier',
      'referral_code',
      'signup_rankings_global',
    ]

    const missing = requiredColumns.filter(c => !columns.includes(c))

    return NextResponse.json({
      needsMigration: missing.length > 0,
      missingColumns: missing,
      existingColumns: columns,
      totalColumns: columns.length,
    })
  } catch (error) {
    return NextResponse.json({
      error: error instanceof Error ? error.message : 'Unknown error',
    }, { status: 500 })
  }
}
