import { NextRequest } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

/**
 * POST /api/workers/me/device-token
 * Persist a mobile push token for the authenticated worker.
 * Body: { token: string, platform: "ios" | "android" | string }
 *
 * Stores { token, platform } into workers.notificationsPushSubscription (jsonb)
 * and sets notificationsPushEnabled=true.
 *
 * Note: mobile push tokens (FCM / APNs) are stored here for future native
 * push integration. The web-push (VAPID) path in site/src/lib/notifications
 * uses the WebPush subscription shape; native FCM/APNs delivery requires a
 * separate server-side integration not yet wired up.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return Response.json({ message: 'Not authenticated' }, { status: 401 })
  }

  let token: string
  let platform: string
  try {
    const body = await request.json()
    token = typeof body?.token === 'string' ? body.token.trim() : ''
    platform = typeof body?.platform === 'string' ? body.platform.trim() : ''
  } catch {
    return Response.json({ message: 'Request body must be valid JSON' }, { status: 400 })
  }

  if (!token) {
    return Response.json({ message: 'token is required' }, { status: 400 })
  }

  await db
    .update(workers)
    .set({
      notificationsPushSubscription: { token, platform } as unknown as Record<string, unknown>,
      notificationsPushEnabled: true,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, auth.workerId))

  return Response.json({ success: true })
}

/**
 * DELETE /api/workers/me/device-token
 * Remove the push token and disable push for the authenticated worker.
 */
export async function DELETE(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return Response.json({ message: 'Not authenticated' }, { status: 401 })
  }

  await db
    .update(workers)
    .set({
      notificationsPushSubscription: null,
      notificationsPushEnabled: false,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, auth.workerId))

  return Response.json({ success: true })
}
