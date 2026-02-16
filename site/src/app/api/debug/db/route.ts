import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, waitlistEntries } from '@/db/schema'
import { sql } from 'drizzle-orm'

export const runtime = 'nodejs'

export async function GET() {
  const steps: string[] = []

  try {
    steps.push('Starting database test')

    steps.push('Connecting to database via Drizzle...')

    steps.push('Counting workers...')
    const [{ workersCount }] = await db
      .select({ workersCount: sql<number>`count(*)` })
      .from(workers)
    steps.push(`Workers count: ${workersCount}`)

    steps.push('Counting waitlist...')
    const [{ waitlistCount }] = await db
      .select({ waitlistCount: sql<number>`count(*)` })
      .from(waitlistEntries)
    steps.push(`Waitlist count: ${waitlistCount}`)

    return NextResponse.json({
      success: true,
      steps,
      counts: {
        workers: Number(workersCount),
        waitlist: Number(waitlistCount),
      },
    })
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'
    const errorStack = error instanceof Error ? error.stack : undefined
    steps.push(`ERROR: ${errorMessage}`)

    return NextResponse.json(
      {
        success: false,
        steps,
        error: errorMessage,
        stack: errorStack,
      },
      { status: 500 }
    )
  }
}
