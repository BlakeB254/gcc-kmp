import { ReactNode } from 'react'

interface SectionWrapperProps {
  children: ReactNode
  /** Background style */
  bg?: 'white' | 'light' | 'dark' | 'green' | 'brown'
  /** Max width of inner content */
  maxWidth?: 'sm' | 'md' | 'lg' | 'xl' | 'full'
  /** Section id for anchor links */
  id?: string
  /** Additional className */
  className?: string
}

const bgClasses = {
  white: 'bg-white',
  light: 'bg-neutral-50',
  dark: 'bg-neutral-900 text-white',
  green: 'bg-brand-green-900 text-white',
  brown: 'bg-gradient-to-br from-brand-brown-800 to-brand-brown-900 text-white',
}

const maxWidthClasses = {
  sm: 'max-w-2xl',
  md: 'max-w-3xl',
  lg: 'max-w-5xl',
  xl: 'max-w-7xl',
  full: '',
}

export function SectionWrapper({
  children,
  bg = 'white',
  maxWidth = 'full',
  id,
  className = '',
}: SectionWrapperProps) {
  return (
    <section
      id={id}
      className={`py-28 lg:py-32 ${bgClasses[bg]} ${className}`}
    >
      <div className={`section-container ${maxWidthClasses[maxWidth]} ${maxWidth !== 'full' ? 'mx-auto' : ''}`}>
        {children}
      </div>
    </section>
  )
}
