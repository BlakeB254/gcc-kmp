'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'

const NAV_ITEMS = [
  { href: '/admin/routes', label: 'Routes & Blocks' },
  { href: '/admin/route-releases', label: 'Route Releases' },
  { href: '/admin/boundaries', label: 'Boundary Editor' },
  { href: '/admin/block-proposals', label: 'Block Proposals' },
  { href: '/admin/sponsors', label: 'Sponsor Deliverables' },
  { href: '/admin/reports', label: 'Completion Reports' },
  { href: '/admin/newsletter', label: 'Newsletter' },
  { href: '/admin/android-testers', label: 'Android Testers' },
  { href: '/admin/payments', label: 'Payments' },
  { href: '/admin/scan-sessions', label: 'Scan Sessions' },
  { href: '/admin/crashes', label: 'Crash Reports' },
]

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const [authed, setAuthed] = useState<boolean | null>(null)

  useEffect(() => {
    const token = localStorage.getItem('adminToken')
    if (!token) {
      // Send unauthenticated visitors to the unified /login page; pass `next`
      // so they land back at the admin route they were trying to reach.
      router.replace(`/login?next=${encodeURIComponent(pathname || '/admin/routes')}`)
      return
    }
    // Validate the token with a lightweight admin-only call.
    fetch('/api/admin/routes', { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => {
        if (r.ok) {
          setAuthed(true)
        } else {
          // Token invalid/expired — punt to /login.
          localStorage.removeItem('adminToken')
          router.replace(`/login?next=${encodeURIComponent(pathname || '/admin/routes')}`)
        }
      })
      .catch(() => {
        router.replace(`/login?next=${encodeURIComponent(pathname || '/admin/routes')}`)
      })
  }, [pathname, router])

  if (authed === null) {
    return (
      <div className="min-h-screen bg-neutral-950 flex items-center justify-center">
        <div className="text-neutral-400 text-sm">Loading…</div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-neutral-950 text-neutral-100 flex">
      {/* Sidebar */}
      <aside className="w-56 border-r border-neutral-800 flex flex-col p-4 gap-1 shrink-0">
        <div className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-3 px-2">
          GCC Admin
        </div>
        {NAV_ITEMS.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={`px-3 py-2 rounded-md text-sm transition-colors ${
              pathname?.startsWith(item.href)
                ? 'bg-neutral-800 text-white font-medium'
                : 'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-900'
            }`}
          >
            {item.label}
          </Link>
        ))}
        <div className="mt-auto">
          <button
            onClick={() => {
              localStorage.removeItem('adminToken')
              router.replace('/login')
            }}
            className="px-3 py-2 rounded-md text-sm text-neutral-500 hover:text-red-400 transition-colors w-full text-left"
          >
            Sign out
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">{children}</main>
    </div>
  )
}
