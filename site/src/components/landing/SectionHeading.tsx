import type { ReactNode } from 'react'

interface SectionHeadingProps {
  /** Small uppercase pill label above the title */
  eyebrow?: ReactNode
  /** Optional icon rendered inside the eyebrow pill */
  eyebrowIcon?: ReactNode
  title: ReactNode
  description?: ReactNode
  /** 'light' for white/neutral sections, 'dark' for brand-green/dark sections */
  tone?: 'light' | 'dark'
  align?: 'center' | 'left'
  className?: string
}

/**
 * Shared header for landing-page sections so every section opens with the
 * same eyebrow → title → lede rhythm instead of ad-hoc pills and sizes.
 */
export function SectionHeading({
  eyebrow,
  eyebrowIcon,
  title,
  description,
  tone = 'light',
  align = 'center',
  className = '',
}: SectionHeadingProps) {
  const isDark = tone === 'dark'
  const alignClasses = align === 'center' ? 'text-center mx-auto' : 'text-center lg:text-left'

  return (
    <div className={`max-w-3xl ${alignClasses} ${className}`}>
      {eyebrow && (
        <span
          className={`section-eyebrow mb-5 ${
            isDark
              ? 'bg-white/10 text-brand-green-200 ring-1 ring-inset ring-white/15'
              : 'bg-brand-green-100 text-brand-green-700'
          }`}
        >
          {eyebrowIcon}
          {eyebrow}
        </span>
      )}
      <h2 className={`section-title mb-4 ${isDark ? 'text-white' : 'text-neutral-900'}`}>
        {title}
      </h2>
      {description && (
        <p className={`section-lede ${isDark ? 'text-brand-green-200' : 'text-neutral-600'}`}>
          {description}
        </p>
      )}
    </div>
  )
}
