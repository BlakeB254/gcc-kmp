import Link from 'next/link'
import Image from 'next/image'

export function WorkerCTASection() {
  const benefits = [
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      ),
      title: '$100 Per Block',
      description: 'Earn real money for cleaning one city block. Paid instantly when approved.',
    },
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      ),
      title: 'Flexible Schedule',
      description: 'Work when you want. Claim blocks that fit your schedule.',
    },
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
      ),
      title: 'Work Your Neighborhood',
      description: 'Clean blocks near you. Make a visible impact where you live.',
    },
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
        </svg>
      ),
      title: 'No Experience Needed',
      description: 'We provide training and equipment. Just bring your hustle.',
    },
  ]

  return (
    <section className="py-28 lg:py-32 bg-brand-brown-900 relative overflow-hidden">
      {/* Background image with overlay - optimized for mobile */}
      <div className="absolute inset-0">
        <picture>
          <source
            media="(max-width: 640px)"
            srcSet="/images/optimized/hero-worker-mobile.webp"
            type="image/webp"
          />
          <source
            srcSet="/images/optimized/hero-worker-desktop.webp"
            type="image/webp"
          />
          <Image
            src="/images/optimized/hero-worker-desktop.jpg"
            alt="GCC worker cleaning a Chicago street"
            fill
            className="object-cover object-center opacity-20"
            sizes="100vw"
          />
        </picture>
        <div className="absolute inset-0 bg-gradient-to-r from-brand-brown-900 via-brand-brown-900/95 to-brand-brown-900/80" />
      </div>

      <div className="section-container relative z-10">
        <div className="grid lg:grid-cols-2 gap-16 items-center">
          {/* Left column - Image */}
          <div className="relative hidden lg:block">
            <div className="relative aspect-[4/5] rounded-2xl overflow-hidden shadow-2xl">
              <picture>
                <source
                  srcSet="/images/optimized/hero-worker-tablet.webp"
                  type="image/webp"
                />
                <Image
                  src="/images/optimized/hero-worker-tablet.jpg"
                  alt="GCC worker cleaning a Chicago street"
                  fill
                  className="object-cover"
                  sizes="(max-width: 1024px) 100vw, 50vw"
                />
              </picture>
              {/* Overlay badge */}
              <div className="absolute bottom-6 left-6 right-6 bg-black/70 backdrop-blur-sm rounded-xl p-4">
                <div className="flex items-center gap-4">
                  <div className="flex-shrink-0 w-12 h-12 rounded-full bg-brand-green-500 flex items-center justify-center">
                    <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                  </div>
                  <div>
                    <p className="text-white font-semibold">Launching Today</p>
                    <p className="text-neutral-400 text-sm">Be among the first to earn</p>
                  </div>
                </div>
              </div>
            </div>
            {/* Decorative elements */}
            <div className="absolute -z-10 -bottom-4 -right-4 w-full h-full rounded-2xl bg-brand-gold-500/30" />
          </div>

          {/* Right column - Content */}
          <div>
            <div className="inline-flex items-center gap-2 rounded-full bg-brand-green-500/20 px-4 py-2 mb-6">
              <svg className="w-4 h-4 text-brand-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 13.255A23.931 23.931 0 0112 15c-3.183 0-6.22-.62-9-1.745M16 6V4a2 2 0 00-2-2h-4a2 2 0 00-2 2v2m4 6h.01M5 20h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
              <span className="text-sm font-medium text-brand-green-400">For Workers</span>
            </div>

            <h2 className="text-3xl sm:text-4xl font-bold text-white mb-6">
              Turn Your Time Into{' '}
              <span className="text-brand-gold-400">Real Income</span>
            </h2>
            <p className="text-lg text-neutral-300 mb-8">
              Join dozens of North Lawndale residents earning money while making their
              neighborhood cleaner. No boss. No commute. Just you, your block, and a paycheck.
            </p>

            {/* Benefits */}
            <div className="grid sm:grid-cols-2 gap-6 mb-10">
              {benefits.map((benefit, index) => (
                <div key={index} className="flex gap-4">
                  <div className="flex-shrink-0 w-10 h-10 rounded-lg bg-brand-green-500/20 flex items-center justify-center text-brand-green-400">
                    {benefit.icon}
                  </div>
                  <div>
                    <h3 className="font-semibold text-white mb-1">{benefit.title}</h3>
                    <p className="text-sm text-neutral-400">{benefit.description}</p>
                  </div>
                </div>
              ))}
            </div>

            {/* CTA Buttons */}
            <div className="flex flex-col sm:flex-row gap-4">
              <Link
                href="/worker/signup"
                className="btn-primary text-center"
              >
                Apply to Become a Cleaner
              </Link>
              <Link
                href="/worker/benefits"
                className="inline-flex items-center justify-center rounded-lg border-2 border-white/60 bg-transparent px-6 py-3 text-base font-semibold text-white transition-all hover:bg-white/10 hover:border-white text-center"
              >
                Learn More
              </Link>
            </div>

            {/* Launch info */}
            <div className="mt-10 pt-8 border-t border-neutral-700 grid grid-cols-3 gap-6">
              <div>
                <p className="text-2xl font-bold text-brand-gold-400">$100</p>
                <p className="text-sm text-neutral-500">Per Block</p>
              </div>
              <div>
                <p className="text-2xl font-bold text-brand-green-400">100%</p>
                <p className="text-sm text-neutral-500">To Workers</p>
              </div>
              <div>
                <p className="text-2xl font-bold text-brand-sky-400">Now</p>
                <p className="text-sm text-neutral-500">Hiring</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
