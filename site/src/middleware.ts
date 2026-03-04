import { NextRequest, NextResponse } from 'next/server'

/**
 * Subdomain routing middleware.
 * Detects north-lawndale.globalcommunitycleaners.org and rewrites
 * requests to the /north-lawndale route directory.
 *
 * For local dev, use ?subdomain=north-lawndale to simulate.
 */
export function middleware(request: NextRequest) {
  const host = request.headers.get('host') || ''

  // Fast path: most requests are not for the subdomain
  if (!host.startsWith('north-lawndale.')) {
    if (request.nextUrl.searchParams.get('subdomain') !== 'north-lawndale') {
      return NextResponse.next()
    }
  }

  // Only rewrite page routes — pass through API, assets, and files with extensions
  const { pathname } = request.nextUrl
  if (
    pathname.startsWith('/api/') ||
    pathname.startsWith('/_next/') ||
    pathname.startsWith('/images/') ||
    pathname.startsWith('/fonts/') ||
    pathname.includes('.')
  ) {
    return NextResponse.next()
  }

  const url = request.nextUrl.clone()
  url.pathname = `/north-lawndale${pathname}`
  return NextResponse.rewrite(url)
}

export const config = {
  matcher: [
    '/((?!_next/static|_next/image|favicon.ico).*)',
  ],
}
