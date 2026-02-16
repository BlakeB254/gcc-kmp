import Link from 'next/link'

interface CTABannerProps {
  heading: string
  subtext: string
  buttonLabel: string
  href: string
  variant?: 'green' | 'gold' | 'outline'
}

const variantClasses = {
  green: 'bg-brand-green-600 hover:bg-brand-green-700 text-white',
  gold: 'bg-brand-gold-500 hover:bg-brand-gold-600 text-white',
  outline: 'border-2 border-white/80 bg-white/10 text-white hover:bg-white/20',
}

export function CTABanner({
  heading,
  subtext,
  buttonLabel,
  href,
  variant = 'green',
}: CTABannerProps) {
  return (
    <div className="bg-white/5 rounded-2xl p-6 sm:p-8 border border-white/10 text-center">
      <h3 className="text-xl font-bold mb-2">{heading}</h3>
      <p className="text-neutral-300 mb-6 max-w-lg mx-auto">{subtext}</p>
      <Link
        href={href}
        className={`inline-flex items-center gap-2 px-6 py-3 rounded-lg font-semibold transition-colors ${variantClasses[variant]}`}
      >
        {buttonLabel}
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
      </Link>
    </div>
  )
}
