import Link from 'next/link'

interface PageHeroProps {
  title: string
  subtitle?: string
  /** Breadcrumb trail, e.g. [{ label: 'Home', href: '/' }, { label: 'Workers' }] */
  breadcrumb?: { label: string; href?: string }[]
}

export function PageHero({ title, subtitle, breadcrumb }: PageHeroProps) {
  return (
    <div className="text-center mb-10">
      {breadcrumb && breadcrumb.length > 0 && (
        <nav className="flex items-center justify-center gap-2 text-sm text-neutral-500 mb-4">
          {breadcrumb.map((crumb, i) => (
            <span key={i} className="flex items-center gap-2">
              {i > 0 && <span>/</span>}
              {crumb.href ? (
                <Link href={crumb.href} className="hover:text-brand-gold-600 transition-colors">
                  {crumb.label}
                </Link>
              ) : (
                <span className="text-neutral-900 font-medium">{crumb.label}</span>
              )}
            </span>
          ))}
        </nav>
      )}
      <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-4">{title}</h1>
      {subtitle && (
        <p className="text-lg text-neutral-600 max-w-2xl mx-auto">{subtitle}</p>
      )}
    </div>
  )
}
