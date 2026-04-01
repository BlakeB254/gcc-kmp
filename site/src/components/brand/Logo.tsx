'use client'

import Link from 'next/link'
import Image from 'next/image'
import { cn } from '@/lib/utils'

interface LogoProps {
  /** Show full name or just "GCC" */
  variant?: 'short' | 'full'
  /** Size of the logo */
  size?: 'sm' | 'md' | 'lg'
  /** Whether the logo links to home */
  linkToHome?: boolean
  /** Additional className */
  className?: string
  /** Theme for dark backgrounds */
  theme?: 'light' | 'dark'
}

const sizeConfig = {
  sm: {
    container: 'h-8 w-8',
    text: 'text-lg',
    fullText: 'text-sm',
  },
  md: {
    container: 'h-10 w-10',
    text: 'text-xl',
    fullText: 'text-base',
  },
  lg: {
    container: 'h-12 w-12',
    text: 'text-2xl',
    fullText: 'text-lg',
  },
}

export function Logo({
  variant = 'short',
  size = 'md',
  linkToHome = true,
  className,
  theme = 'light',
}: LogoProps) {
  const config = sizeConfig[size]

  const content = (
    <div className={cn('flex items-center gap-2', className)}>
      {/* Logo Image */}
      <div className={cn('relative overflow-hidden', config.container)}>
        <Image
          src="/images/gcc-symbol.png"
          alt="GCC Logo"
          fill
          className="object-contain"
          priority
        />
      </div>

      {/* Text */}
      <span
        className={cn(
          'font-bold',
          variant === 'short' ? config.text : config.fullText,
          theme === 'light' ? 'text-brand-green-800' : 'text-white'
        )}
      >
        {variant === 'full' ? 'Global Community Cleaners' : 'GCC'}
      </span>
    </div>
  )

  if (linkToHome) {
    return (
      <Link href="/" className="inline-flex items-center hover:opacity-90 transition-opacity">
        {content}
      </Link>
    )
  }

  return content
}

/**
 * Fallback logo using just text/icon (for cases where image might not load)
 */
export function LogoFallback({
  variant = 'short',
  size = 'md',
  linkToHome = true,
  className,
  theme = 'light',
}: LogoProps) {
  const config = sizeConfig[size]

  const content = (
    <div className={cn('flex items-center gap-2', className)}>
      {/* Icon Circle */}
      <div
        className={cn(
          'rounded-full bg-brand-green-500 flex items-center justify-center',
          config.container
        )}
      >
        <span className="text-white font-bold" style={{ fontSize: size === 'sm' ? '14px' : size === 'md' ? '18px' : '22px' }}>
          G
        </span>
      </div>

      {/* Text */}
      <span
        className={cn(
          'font-bold',
          variant === 'short' ? config.text : config.fullText,
          theme === 'light' ? 'text-brand-green-800' : 'text-white'
        )}
      >
        {variant === 'full' ? 'Global Community Cleaners' : 'GCC'}
      </span>
    </div>
  )

  if (linkToHome) {
    return (
      <Link href="/" className="inline-flex items-center hover:opacity-90 transition-opacity">
        {content}
      </Link>
    )
  }

  return content
}
