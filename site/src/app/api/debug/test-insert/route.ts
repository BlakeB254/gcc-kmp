import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
import { sql } from 'drizzle-orm'

export const runtime = 'nodejs'

export async function GET() {
  const steps: string[] = []

  try {
    steps.push('Starting insert test')

    // Test 1: Check if workers table exists and has the right columns
    steps.push('Checking workers table structure...')
    const columnsResult = await db.execute(sql`
      SELECT column_name, data_type
      FROM information_schema.columns
      WHERE table_name = 'workers'
      ORDER BY ordinal_position
      LIMIT 20
    `)
    steps.push(`Workers table has ${columnsResult.rows.length} columns (showing first 20)`)

    const columnNames = columnsResult.rows.map((r) => (r as { column_name: string }).column_name)
    steps.push(`Columns: ${columnNames.join(', ')}`)

    // Test 2: Check if media table exists
    steps.push('Checking media table...')
    const mediaResult = await db.execute(sql`
      SELECT column_name FROM information_schema.columns WHERE table_name = 'media' LIMIT 5
    `)
    steps.push(`Media table has ${mediaResult.rows.length} columns`)

    // Test 3: Check if required enums exist
    steps.push('Checking enum types...')
    const enumsResult = await db.execute(sql`
      SELECT typname FROM pg_type WHERE typtype = 'e' AND typname LIKE 'enum_%' LIMIT 10
    `)
    const enumNames = enumsResult.rows.map((r) => (r as { typname: string }).typname)
    steps.push(`Enums: ${enumNames.join(', ')}`)

    return NextResponse.json({
      success: true,
      steps,
      columns: columnNames,
      enums: enumNames,
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
