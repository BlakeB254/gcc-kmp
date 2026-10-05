import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'

export function FooterSection() {
  const workerLinks = [
    { href: '/how-it-works?tab=workers', label: 'How It Works' },
    { href: '/worker/signup', label: 'Sign Up to Earn' },
    { href: '/login', label: 'Sign In' },
    { href: '/worker/leaderboard', label: 'Leaderboard' },
    { href: '/android-beta', label: 'Test the Android App' },
    { href: '/faq', label: 'FAQ' },
  ]

  const sponsorLinks = [
    { href: '/how-it-works?tab=sponsors', label: 'How It Works' },
    { href: '/sponsor', label: 'Sponsor a Block' },
    { href: '/business', label: 'For Businesses' },
    { href: '/sponsor/calculator', label: 'Custom Package' },
    { href: '/sponsor/enterprise', label: 'Enterprise' },
    { href: '/login', label: 'Sign In' },
    { href: '/impact-supporters', label: 'Our Supporters' },
  ]

  const aboutLinks = [
    { href: '/about', label: 'About GCC' },
    { href: '/impact-supporters', label: 'Impact & Supporters' },
    { href: '/bounties', label: 'Community Bounties' },
    { href: '/join', label: 'Join Movement' },
    { href: '/faq', label: 'FAQ' },
    { href: '/privacy', label: 'Privacy Policy' },
    { href: '/terms', label: 'Terms of Service' },
  ]

  return (
    <footer className="bg-neutral-900 text-white">
      {/* Brand accent line */}
      <div className="h-1 bg-gradient-to-r from-brand-green-500 via-brand-blue-500 to-brand-sky-500" />
      <div className="section-container py-16">
        <div className="grid md:grid-cols-2 lg:grid-cols-5 gap-12 lg:gap-8">
          {/* Brand Section */}
          <div className="lg:col-span-2">
            <div className="mb-6 hover-scale inline-block">
              <Logo variant="full" size="md" theme="dark" />
            </div>
            <p className="text-neutral-400 max-w-sm leading-relaxed">
              Connecting neighborhood residents worldwide with paid block-cleaning opportunities.
              <strong className="text-white"> 100% of every $100</strong> goes directly to workers.
            </p>
          </div>

          {/* Worker Links */}
          <div>
            <h4 className="font-semibold mb-4 text-white">For Workers</h4>
            <ul className="space-y-2">
              {workerLinks.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="text-neutral-400 hover:text-white transition-colors duration-200 text-sm"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Sponsor Links */}
          <div>
            <h4 className="font-semibold mb-4 text-white">For Sponsors</h4>
            <ul className="space-y-2">
              {sponsorLinks.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="text-neutral-400 hover:text-white transition-colors duration-200 text-sm"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* About Links */}
          <div>
            <h4 className="font-semibold mb-4 text-white">About</h4>
            <ul className="space-y-2">
              {aboutLinks.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="text-neutral-400 hover:text-white transition-colors duration-200 text-sm"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* Bottom Bar */}
        <div className="border-t border-neutral-800 mt-12 pt-8 flex flex-col sm:flex-row justify-between items-center gap-4">
          <p className="text-neutral-400 text-sm">
            © {new Date().getFullYear()} Global Community Cleaners. A 501(c)(3) nonprofit organization.
          </p>
          <p className="text-neutral-500 text-sm">
            <a href="mailto:info@globalcommunitycleaners.org" className="hover:text-white transition-colors">
              info@globalcommunitycleaners.org
            </a>
          </p>
        </div>
      </div>
    </footer>
  )
}
