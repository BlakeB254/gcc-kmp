import { Logo } from '@/components/brand/Logo'
import { ExternalLink } from 'lucide-react'

export function NLFooterSection() {
  return (
    <footer className="bg-neutral-900 text-white">
      <div className="section-container py-12">
        <div className="max-w-2xl mx-auto text-center">
          {/* Logo */}
          <div className="mb-6 inline-block">
            <Logo variant="full" size="md" theme="dark" />
          </div>

          {/* Mission */}
          <p className="text-neutral-400 leading-relaxed mb-8">
            Connecting North Lawndale residents with paid block cleaning opportunities.
            <strong className="text-white"> 100% of every $100</strong> goes directly to workers.
          </p>

          {/* Link to main site */}
          <a
            href="https://globalcommunitycleaners.org"
            className="inline-flex items-center gap-2 text-sm font-medium text-brand-gold-400 hover:text-brand-gold-300 transition-colors"
          >
            Visit Global Community Cleaners
            <ExternalLink className="w-4 h-4" />
          </a>
        </div>

        {/* Bottom Bar */}
        <div className="border-t border-neutral-800 mt-10 pt-6 flex flex-col sm:flex-row justify-between items-center gap-4">
          <p className="text-neutral-500 text-sm">
            &copy; {new Date().getFullYear()} Global Community Cleaners. A 501(c)(3) nonprofit organization.
          </p>
          <div className="flex items-center gap-4 text-sm text-neutral-500">
            <a
              href="https://globalcommunitycleaners.org/privacy"
              className="hover:text-white transition-colors"
            >
              Privacy
            </a>
            <a
              href="https://globalcommunitycleaners.org/terms"
              className="hover:text-white transition-colors"
            >
              Terms
            </a>
            <a
              href="mailto:info@globalcommunitycleaners.org"
              className="hover:text-white transition-colors"
            >
              info@globalcommunitycleaners.org
            </a>
          </div>
        </div>
      </div>
    </footer>
  )
}
