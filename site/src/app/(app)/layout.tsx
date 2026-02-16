'use client'

import '../globals.css'
import { usePathname } from 'next/navigation'
import { Header } from '@/components/landing/Header'
import { FooterSection } from '@/components/landing/FooterSection'

export default function AppLayout({
  children,
}: {
  children: React.ReactNode
}) {
  const pathname = usePathname()

  // Authenticated pages and special pages have their own navigation
  const isAuthenticatedPage =
    pathname.includes('/dashboard') ||
    pathname.includes('/pending') ||
    pathname.includes('/approved') ||
    pathname.includes('/available') ||
    pathname.includes('/profile') ||
    pathname.includes('/job/') ||
    pathname.includes('/enterprise') || // Dark themed page with own header
    pathname.startsWith('/support') ||
    pathname.startsWith('/community')

  if (isAuthenticatedPage) {
    return (
      <div className="min-h-screen bg-neutral-50">
        {children}
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-neutral-50 flex flex-col">
      <Header />
      <main className="flex-1 pt-16">
        {children}
      </main>
      <FooterSection />
    </div>
  )
}
