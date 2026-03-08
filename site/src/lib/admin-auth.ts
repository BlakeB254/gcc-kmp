import { NextResponse } from 'next/server'
import { verifyMobileToken } from './mobile-auth'

/**
 * Verify the request is from an authenticated admin user.
 * Returns { workerId, role } on success, or a NextResponse error.
 */
export async function requireAdmin(
  request: Request
): Promise<{ workerId: number; role: string } | NextResponse> {
  const authHeader = request.headers.get('Authorization')
  if (!authHeader?.startsWith('Bearer ')) {
    return NextResponse.json({ error: 'Missing authorization' }, { status: 401 })
  }

  const token = authHeader.slice(7)
  const user = await verifyMobileToken(token)

  if (!user) {
    return NextResponse.json({ error: 'Invalid or expired token' }, { status: 401 })
  }

  if (user.role !== 'admin') {
    return NextResponse.json({ error: 'Admin access required' }, { status: 403 })
  }

  return user
}
