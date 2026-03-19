import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyPassword, createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { email, password } = body

    if (!email || !password) {
      return Response.json(
        { data: null, success: false, message: 'Invalid email or password' },
        { status: 401 }
      )
    }

    // Find worker with credentials
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    if (!worker || !worker.salt || !worker.hash) {
      return Response.json(
        { data: null, success: false, message: 'Invalid email or password' },
        { status: 401 }
      )
    }

    // Verify password
    if (!verifyPassword(password, worker.hash, worker.salt)) {
      return Response.json(
        { data: null, success: false, message: 'Invalid email or password' },
        { status: 401 }
      )
    }

    // Generate JWT
    const { token, exp } = await createMobileToken(worker.id, email)

    // Map to mobile format
    const mobileWorker = mapWorkerToMobile(worker)

    return Response.json({
      data: {
        token,
        worker: mobileWorker,
        exp,
        requiresTwoFactor: false,
        twoFactorToken: null,
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Login error:', error)
    return Response.json(
      { data: null, success: false, message: 'Authentication failed' },
      { status: 500 }
    )
  }
}
