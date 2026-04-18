'use client'

import { useState, useEffect } from 'react'
import { Logo } from '@/components/brand/Logo'

export function NLHeader() {
  const [isScrolled, setIsScrolled] = useState(false)

  useEffect(() => {
    const handleScroll = () => {
      setIsScrolled(window.scrollY > 50)
    }
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => window.removeEventListener('scroll', handleScroll)
  }, [])

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
        <div className="hover-scale">
          <Logo size="md" variant="short" linkToHome />
        </div>

        {/* Single CTA — scroll to signup */}
        <a
          href="#join"
          className="text-sm font-semibold py-2 px-5 rounded-lg bg-brand-gold-500 text-white hover:bg-brand-gold-600 transition-colors"
        >
          Join the Movement
        </a>
      </nav>
    </header>
  )
}
