'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Logo } from '@/components/brand/Logo'
import { Menu, X, Heart } from 'lucide-react'

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [isScrolled, setIsScrolled] = useState(false)
  const pathname = usePathname()

  // Don't render header on authenticated pages or special themed pages
  const isAuthenticatedPage =
    pathname.includes('/dashboard') ||
    pathname.includes('/pending') ||
    pathname.includes('/approved') ||
    pathname.includes('/available') ||
    pathname.includes('/profile') ||
    pathname.includes('/job/') ||
    pathname.includes('/enterprise') ||
    pathname === '/support' ||
    pathname.startsWith('/support/') ||
    pathname.startsWith('/community')

  if (isAuthenticatedPage) {
    return null
  }

  // Track scroll position for header styling
  useEffect(() => {
    const handleScroll = () => {
      setIsScrolled(window.scrollY > 50)
    }
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => window.removeEventListener('scroll', handleScroll)
  }, [])

  const navLinks = [
    { label: 'Workers', href: '/worker/benefits' },
    { label: 'Sponsors', href: '/sponsor/benefits' },
    { label: 'Bounties', href: '/bounties' },
    { label: 'Impact', href: '/impact' },
    { label: 'Supporters', href: '/supporters' },
    { label: 'FAQ', href: '/faq' },
  ]

  return (
    <header
      className={`
        fixed top-0 z-50 w-full transition-all duration-300 ease-out
        ${isScrolled
          ? 'bg-white/98 backdrop-blur-md shadow-sm border-b border-neutral-100'
          : 'bg-white/95 backdrop-blur-sm border-b border-transparent'
        }
      `}
    >
      <nav className="section-container flex h-16 items-center justify-between">
        {/* Logo */}
        <div className="hover-scale">
          <Logo size="md" variant="short" />
        </div>

        {/* Desktop Navigation */}
        <div className="hidden lg:flex lg:items-center gap-1">
          {navLinks.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className={`px-3 py-2 text-sm font-medium rounded-lg transition-colors ${
                pathname.startsWith(link.href.split('/').slice(0, 2).join('/'))
                  ? 'text-brand-green-600 bg-brand-green-50'
                  : 'text-neutral-600 hover:text-brand-green-700 hover:bg-neutral-50'
              }`}
            >
              {link.label}
            </Link>
          ))}
        </div>

        {/* Desktop CTA Buttons */}
        <div className="hidden lg:flex lg:items-center gap-3">
          <Link
            href="/donate"
            className="text-sm font-semibold py-2 px-5 rounded-lg bg-brand-green-600 text-white hover:bg-brand-green-700 transition-colors flex items-center gap-1.5"
          >
            <Heart className="w-4 h-4" />
            Donate
          </Link>
          <Link
            href="/worker/signup"
            className="text-sm font-medium py-2 px-4 rounded-lg border-2 border-brand-green-500 text-brand-green-700 hover:bg-brand-green-50 transition-colors"
          >
            Start Earning
          </Link>
        </div>

        {/* Mobile Menu Button */}
        <button
          type="button"
          className="lg:hidden p-2 -mr-2 rounded-lg"
          onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
          aria-label="Toggle menu"
        >
          {mobileMenuOpen ? (
            <X className="h-6 w-6 text-neutral-600" />
          ) : (
            <Menu className="h-6 w-6 text-neutral-600" />
          )}
        </button>
      </nav>

      {/* Mobile Menu */}
      <div
        className={`
          lg:hidden overflow-hidden transition-all duration-300 ease-out
          ${mobileMenuOpen ? 'max-h-[80vh] opacity-100' : 'max-h-0 opacity-0'}
        `}
      >
        <div className="border-t border-neutral-100 bg-white py-3 max-h-[calc(80vh-64px)] overflow-y-auto">
          <div className="section-container space-y-1">
            {navLinks.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                className="block px-3 py-2.5 text-sm font-medium text-neutral-700 hover:bg-neutral-50 rounded-lg"
                onClick={() => setMobileMenuOpen(false)}
              >
                {link.label}
              </Link>
            ))}

            <hr className="border-neutral-200 my-2" />

            {/* Compact CTAs */}
            <div className="flex gap-2 pt-1">
              <Link
                href="/donate"
                className="flex-1 py-2.5 px-4 text-sm font-semibold text-center text-white bg-brand-green-600 hover:bg-brand-green-700 rounded-lg transition-colors flex items-center justify-center gap-1.5"
                onClick={() => setMobileMenuOpen(false)}
              >
                <Heart className="w-4 h-4" />
                Donate
              </Link>
              <Link
                href="/worker/signup"
                className="flex-1 py-2.5 px-4 text-sm font-semibold text-center text-brand-green-700 border-2 border-brand-green-500 hover:bg-brand-green-50 rounded-lg transition-colors"
                onClick={() => setMobileMenuOpen(false)}
              >
                Start Earning
              </Link>
            </div>
          </div>
        </div>
      </div>
    </header>
  )
}
