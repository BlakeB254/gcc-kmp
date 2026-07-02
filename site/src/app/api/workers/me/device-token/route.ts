import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

/**
 * POST /api/workers/me/device-token
 * Body: { platform: "ios" | "android" | "web", token: string }
 *
 * Persists the worker's push token so the notification dispatcher can reach
 * them. For iOS this is the raw APNs device token (hex). Stored on the worker
 * row as `notifications_push_subscription = { platform, token }` and flips
 * `notifications_push_enabled = true`.
 *
 * (Previously a no-op stub that returned success without storing anything —
 * which is why no push could ever be delivered in production.)
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  }

  let body: { platform?: unknown; token?: unknown }
  try {
    body = await request.json()
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body', success: false }, { status: 400 })
  }

  const platform = typeof body.platform === 'string' ? body.platform.trim() : ''
  const token = typeof body.token === 'string' ? body.token.trim() : ''
  if (!platform || !token) {
    return NextResponse.json(
      { error: 'platform and token are required', success: false },
      { status: 400 }
    )
  }

  await db
    .update(workers)
    .set({
      notificationsPushEnabled: true,
      notificationsPushSubscription: { platform, token },
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, auth.workerId))

  return NextResponse.json({ success: true })
}

/**
 * DELETE /api/workers/me/device-token
 * Clears the stored push token and disables push (e.g. on logout / opt-out).
 */
export async function DELETE(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  }

  await db
    .update(workers)
    .set({
      notificationsPushEnabled: false,
      notificationsPushSubscription: null,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, auth.workerId))

  return NextResponse.json({ success: true })
}
