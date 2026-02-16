import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'

export function FooterSection() {
  const socialLinks = [
    {
      name: 'Facebook',
      href: 'https://facebook.com/globalcommunitycleaners',
      icon: (
        <path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z" />
      ),
      hoverColor: 'hover:bg-blue-600',
    },
    {
      name: 'Instagram',
      href: 'https://instagram.com/gccchicago',
      icon: (
        <path d="M12.315 2c2.43 0 2.784.013 3.808.06 1.064.049 1.791.218 2.427.465a4.902 4.902 0 011.772 1.153 4.902 4.902 0 011.153 1.772c.247.636.416 1.363.465 2.427.048 1.067.06 1.407.06 4.123v.08c0 2.643-.012 2.987-.06 4.043-.049 1.064-.218 1.791-.465 2.427a4.902 4.902 0 01-1.153 1.772 4.902 4.902 0 01-1.772 1.153c-.636.247-1.363.416-2.427.465-1.067.048-1.407.06-4.123.06h-.08c-2.643 0-2.987-.012-4.043-.06-1.064-.049-1.791-.218-2.427-.465a4.902 4.902 0 01-1.772-1.153 4.902 4.902 0 01-1.153-1.772c-.247-.636-.416-1.363-.465-2.427-.047-1.024-.06-1.379-.06-3.808v-.63c0-2.43.013-2.784.06-3.808.049-1.064.218-1.791.465-2.427a4.902 4.902 0 011.153-1.772A4.902 4.902 0 015.45 2.525c.636-.247 1.363-.416 2.427-.465C8.901 2.013 9.256 2 11.685 2h.63zm-.081 1.802h-.468c-2.456 0-2.784.011-3.807.058-.975.045-1.504.207-1.857.344-.467.182-.8.398-1.15.748-.35.35-.566.683-.748 1.15-.137.353-.3.882-.344 1.857-.047 1.023-.058 1.351-.058 3.807v.468c0 2.456.011 2.784.058 3.807.045.975.207 1.504.344 1.857.182.466.399.8.748 1.15.35.35.683.566 1.15.748.353.137.882.3 1.857.344 1.054.048 1.37.058 4.041.058h.08c2.597 0 2.917-.01 3.96-.058.976-.045 1.505-.207 1.858-.344.466-.182.8-.398 1.15-.748.35-.35.566-.683.748-1.15.137-.353.3-.882.344-1.857.048-1.055.058-1.37.058-4.041v-.08c0-2.597-.01-2.917-.058-3.96-.045-.976-.207-1.505-.344-1.858a3.097 3.097 0 00-.748-1.15 3.098 3.098 0 00-1.15-.748c-.353-.137-.882-.3-1.857-.344-1.023-.047-1.351-.058-3.807-.058zM12 6.865a5.135 5.135 0 110 10.27 5.135 5.135 0 010-10.27zm0 1.802a3.333 3.333 0 100 6.666 3.333 3.333 0 000-6.666zm5.338-3.205a1.2 1.2 0 110 2.4 1.2 1.2 0 010-2.4z" />
      ),
      hoverColor: 'hover:bg-gradient-to-br hover:from-purple-600 hover:via-pink-500 hover:to-orange-400',
    },
    {
      name: 'X (Twitter)',
      href: 'https://twitter.com/gccchicago',
      icon: (
        <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
      ),
      hoverColor: 'hover:bg-neutral-600',
    },
  ]

  const workerLinks = [
    { href: '/worker/benefits', label: 'How It Works' },
    { href: '/worker/signup', label: 'Sign Up to Earn' },
    { href: '/worker/login', label: 'Worker Login' },
    { href: '/worker/leaderboard', label: 'Leaderboard' },
    { href: '/faq', label: 'FAQ' },
  ]

  const sponsorLinks = [
    { href: '/sponsor/benefits', label: 'How It Works' },
    { href: '/sponsor', label: 'Sponsor a Block' },
    { href: '/sponsor/calculator', label: 'Custom Package' },
    { href: '/sponsor/enterprise', label: 'Enterprise' },
    { href: '/sponsor/login', label: 'Sponsor Login' },
    { href: '/sponsor/wall', label: 'Sponsor Wall' },
  ]

  const aboutLinks = [
    { href: '/impact', label: 'Our Impact' },
    { href: '/join', label: 'Join Movement' },
    { href: '/faq', label: 'FAQ' },
    { href: '/privacy', label: 'Privacy Policy' },
    { href: '/terms', label: 'Terms of Service' },
  ]

  return (
    <footer className="bg-neutral-900 text-white">
      <div className="section-container py-16">
        <div className="grid md:grid-cols-2 lg:grid-cols-5 gap-12 lg:gap-8">
          {/* Brand Section */}
          <div className="lg:col-span-2">
            <div className="mb-6 hover-scale inline-block">
              <Logo variant="full" size="md" theme="dark" />
            </div>
            <p className="text-neutral-400 max-w-sm mb-6 leading-relaxed">
              Connecting North Lawndale residents with paid block cleaning opportunities.
              <strong className="text-white"> 100% of every $100</strong> goes directly to workers.
            </p>

            {/* Social Links */}
            <div className="flex gap-3">
              {socialLinks.map((social) => (
                <a
                  key={social.name}
                  href={social.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className={`
                    group w-11 h-11 rounded-full bg-neutral-800 flex items-center justify-center
                    transition-all duration-300 ease-out hover:scale-110
                    ${social.hoverColor}
                  `}
                  aria-label={social.name}
                >
                  <svg
                    className="w-5 h-5 transition-transform duration-300 group-hover:scale-110"
                    fill="currentColor"
                    viewBox="0 0 24 24"
                  >
                    {social.icon}
                  </svg>
                </a>
              ))}
            </div>
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
