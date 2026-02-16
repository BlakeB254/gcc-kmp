import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { transactions, sponsors, blocks } from '@/db/schema'
import { eq, desc, isNotNull, sql, and, ne } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * Public Contributions API
 *
 * Returns a paginated list of completed contributions for the support wall.
 * Respects anonymous settings - anonymous contributions show as "Anonymous Supporter".
 *
 * Query params:
 * - page: Page number (default: 1)
 * - limit: Items per page (default: 20, max: 100)
 * - type: Filter by 'donation' | 'sponsorship' | 'all' (default: 'all')
 */

interface ContributionResponse {
  id: number
  type: 'donation' | 'sponsorship'
  amount: number // In dollars
  displayName: string
  isAnonymous: boolean
  message: string | null
  blockName: string | null
  blockCode: string | null
  createdAt: string
  frequency: 'one_time' | 'monthly'
}

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams
  const page = Math.max(1, parseInt(searchParams.get('page') || '1'))
  const limit = Math.min(100, Math.max(1, parseInt(searchParams.get('limit') || '20')))
  const typeFilter = searchParams.get('type') || 'all'
  const offset = (page - 1) * limit

  try {
    // Build the query
    const conditions = [
      eq(transactions.status, 'completed'),
    ]

    if (typeFilter === 'donation') {
      conditions.push(sql`${transactions.blockId} IS NULL`)
    } else if (typeFilter === 'sponsorship') {
      conditions.push(isNotNull(transactions.blockId))
    }

    // Fetch contributions with sponsor and block details
    const results = await db
      .select({
        id: transactions.id,
        amount: transactions.amount,
        displayName: transactions.displayName,
        isAnonymous: transactions.isAnonymous,
        message: transactions.message,
        frequency: transactions.sponsorType,
        blockId: transactions.blockId,
        createdAt: transactions.createdAt,
        sponsorName: sponsors.name,
        businessName: sponsors.businessName,
        blockName: blocks.name,
        blockCode: blocks.code,
      })
      .from(transactions)
      .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
      .leftJoin(blocks, eq(transactions.blockId, blocks.id))
      .where(and(...conditions))
      .orderBy(desc(transactions.createdAt))
      .limit(limit)
      .offset(offset)

    // Get total count
    const [{ count }] = await db
      .select({ count: sql<number>`count(*)` })
      .from(transactions)
      .where(and(...conditions))

    // Transform results for public display
    const contributions: ContributionResponse[] = results.map((row) => {
      const isAnonymous = row.isAnonymous ?? false

      // Determine display name
      let displayName: string
      if (isAnonymous) {
        displayName = 'Anonymous Supporter'
      } else if (row.displayName) {
        displayName = row.displayName
      } else if (row.businessName) {
        displayName = row.businessName
      } else if (row.sponsorName) {
        displayName = row.sponsorName
      } else {
        displayName = 'Anonymous Supporter'
      }

      return {
        id: row.id,
        type: row.blockId ? 'sponsorship' : 'donation',
        amount: Number(row.amount) / 100, // Convert cents to dollars
        displayName,
        isAnonymous,
        message: isAnonymous ? null : row.message, // Don't show messages for anonymous
        blockName: row.blockName,
        blockCode: row.blockCode,
        createdAt: row.createdAt,
        frequency: (row.frequency as 'one_time' | 'monthly') || 'one_time',
      }
    })

    return NextResponse.json({
      contributions,
      pagination: {
        page,
        limit,
        total: Number(count),
        totalPages: Math.ceil(Number(count) / limit),
        hasMore: page * limit < Number(count),
      },
    })
  } catch (error) {
    console.error('Error fetching contributions:', error)
    return NextResponse.json(
      { error: 'Failed to fetch contributions' },
      { status: 500 }
    )
  }
}
