import { NextRequest, NextResponse } from 'next/server'

/**
 * Subdomain routing middleware.
 *
 * 1. api.globalcommunitycleaners.org — only allow /api/* routes through;
 *    redirect all other requests to www.globalcommunitycleaners.org
 * 2. north-lawndale.globalcommunitycleaners.org — rewrite to /north-lawndale
 *
 * For local dev, use ?subdomain=north-lawndale to simulate.
 */
export function middleware(request: NextRequest) {
  const host = request.headers.get('host') || ''
  const { pathname } = request.nextUrl

  // --- api. subdomain: block frontend, allow API routes only ---
  if (host.startsWith('api.')) {
    if (pathname.startsWith('/api/')) {
      return NextResponse.next()
    }
    // Redirect everything else to the main domain
    const mainUrl = new URL(pathname, 'https://www.globalcommunitycleaners.org')
    mainUrl.search = request.nextUrl.search
    return NextResponse.redirect(mainUrl, 301)
  }

  // --- north-lawndale subdomain routing ---
  if (
    host.startsWith('north-lawndale.') ||
    request.nextUrl.searchParams.get('subdomain') === 'north-lawndale'
  ) {
    // Only rewrite page routes — pass through API, assets, and files with extensions
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

  return NextResponse.next()
}

export const config = {
  matcher: [
    '/((?!_next/static|_next/image|favicon.ico).*)',
  ],
}
