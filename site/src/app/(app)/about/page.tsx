import Link from 'next/link'

export const metadata = {
  title: 'About | Global Community Cleaners',
  description:
    "The history behind Global Community Cleaners — six blocks from Dr. King's 1966 apartment in North Lawndale, the field-evidence layer for community cleanliness, and the founder who built it.",
}

const media = '/newsletter/issue-01'

export default function AboutPage() {
  return (
    <article className="bg-neutral-50 pb-24 pt-12">
      <div className="section-container max-w-3xl">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-brand-green-700">
            About Global Community Cleaners
          </p>
          <h1 className="mt-6 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            Why we do what we do
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-neutral-600">
            Our origins in North Lawndale, why we&rsquo;re not a cleaning service, and the people behind the work.
          </p>
        </header>

        {/* Origin story */}
        <Section label="Our origin">
          <p>
            On <strong>January 26, 1966</strong>, Dr. Martin Luther King Jr. carried his bags up to a third-floor walk-up at <strong>1550 South Hamlin Avenue</strong> in North Lawndale and started what he called the &ldquo;action phase&rdquo; of the Chicago Freedom Movement &mdash; the campaign that brought him here to focus on housing conditions and economic opportunity in Chicago neighborhoods. The campaign produced the Summit Agreement, which fed directly into the 1968 Fair Housing Act.
          </p>
          <p>
            Today, Global Community Cleaners is headquartered at <strong>1844 South Harding, 2nd Floor</strong>, a six-block walk from the apartment Dr. King moved into.
          </p>
          <Pull>Sixty years. Two blocks of distance. Same neighborhood, same work.</Pull>
        </Section>

        {/* Why we exist / positioning */}
        <Section label="Why we exist">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            We&rsquo;re not a cleaning service. We&rsquo;re the field-evidence layer for community cleanliness.
          </h2>
          <p>
            Most organizations talk about a problem. We hold the data on it. Our positioning rests on three pillars:
          </p>
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
            <PillarCard
              n="01"
              name="Historical legitimacy"
              desc="Rooted six blocks from where Dr. King launched the Chicago Freedom Movement's action phase in this exact neighborhood."
            />
            <PillarCard
              n="02"
              name="Academic data"
              desc="Grounded in peer-reviewed evidence — the Branas Philadelphia trial, Sampson's collective-efficacy research — not intuition."
            />
            <PillarCard
              n="03"
              name="Civic-cleanliness data"
              desc="Every hotspot residents log is photographed, GPS-tagged, and timestamped — a real, growing dataset on the ground."
            />
          </div>
          <p className="mt-6">
            We pay the resident on the block to clean it, and we document every inch of it. That documentation &mdash; not the cleaning alone &mdash; is what makes GCC data infrastructure for community cleanliness, not a cleaning service.
          </p>
        </Section>

        {/* Founder's story */}
        <Section label="Meet the founder">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Shaquille Tate built it because he was tired of seeing it.
          </h2>
          <p>
            Born at Mt. Sinai Hospital in North Lawndale. Raised on the blocks running from <strong>15th Street to Ridgeway</strong>. Drives Divvy across the entire city for his day job &mdash; which means he sees what every Chicago neighborhood looks like from the curb. Asked on a 2026 radio interview how the idea found him, he answered:
          </p>
          <FounderQuote>I had to come back to the North Lawndale community, and I&rsquo;m walking past abandoned houses &mdash; boards on the windows, broken windows, trash everywhere on the floor.</FounderQuote>
          <FounderQuote>What if somebody could get all these kids to clean up their block? And if they were to clean up their block, to get paid to clean up their block.</FounderQuote>
          <p className="mt-6">
            It&rsquo;s about cleaning, but it&rsquo;s also about cash, dignity, ownership, and the simple fact that <strong>the people best equipped to take care of a block are the people who live on it</strong> &mdash; if someone is willing to pay them like the work matters. Because it does.
          </p>
        </Section>

        {/* Evidence base */}
        <Section label="The evidence base">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            This model isn&rsquo;t a hunch. It&rsquo;s backed by the research.
          </h2>
          <p>
            Charles Branas&rsquo;s team at Penn ran a cluster-randomized trial across Philadelphia &mdash; the gold standard &mdash; comparing greened lots, trash-removal-only lots, and untouched controls. Published in <em>PNAS</em> and <em>JAMA Network Open</em>, it found a <strong>29% drop in gun assaults</strong> around treated lots, with the strongest effects for low-income residents.
          </p>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={`${media}/stats/branas-rct.png`}
            alt="Branas trial results: 29.1% drop in gun assaults, 41.5% fewer depression reports, 68.7% drop for low-income residents"
            className="mt-6 w-full rounded-xl"
          />
          <p className="mt-6">
            Robert Sampson&rsquo;s Chicago research adds the why: neighborhoods high in <strong>collective efficacy</strong> &mdash; whether neighbors feel any agency over their block &mdash; run roughly <strong>40% lower</strong> crime rates even when income and visible disorder look identical on paper.
          </p>
          <p className="mt-2 font-semibold text-neutral-900">
            Invest in the people on the block, and the block follows. Our job is to give those people the data, the tools, and the paycheck.
          </p>
        </Section>

        {/* CTA */}
        <Section label="Get involved">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            If this resonates, here&rsquo;s exactly what to do with that.
          </h2>
          <div className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Link
              href="/worker/signup"
              className="rounded-lg bg-brand-green-700 px-5 py-4 text-center text-base font-semibold text-white hover:bg-brand-green-800"
            >
              Become a worker
            </Link>
            <Link
              href="/sponsor"
              className="rounded-lg bg-neutral-900 px-5 py-4 text-center text-base font-semibold text-white hover:bg-neutral-800"
            >
              Sponsor a block
            </Link>
            <Link
              href="/blog"
              className="rounded-lg border-2 border-neutral-300 px-5 py-4 text-center text-base font-semibold text-neutral-900 hover:bg-neutral-100"
            >
              Read the blog
            </Link>
          </div>
        </Section>

        {/* Footer / share */}
        <div className="mt-16 border-t border-neutral-200 pt-8 text-center text-sm text-neutral-500">
          <p className="mt-3 text-xs">
            Global Community Cleaners NFP · 1844 S. Harding Ave, 2nd Floor · Chicago, IL 60623 ·
            501(c)(3) tax-exempt · EIN 39-3553882
          </p>
        </div>
      </div>
    </article>
  )
}

function PillarCard({ n, name, desc }: { n: string; name: string; desc: string }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white px-5 py-4">
      <span className="font-serif text-2xl font-bold text-brand-green-700">{n}</span>
      <p className="mt-1 font-bold text-neutral-900">{name}</p>
      <p className="mt-1 text-sm leading-relaxed text-neutral-600">{desc}</p>
    </div>
  )
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <section className="mt-16 border-t-2 border-neutral-200 pt-12 text-base leading-relaxed text-neutral-700">
      <p className="mb-4 text-xs font-bold uppercase tracking-[0.18em] text-brand-green-700">{label}</p>
      <div className="space-y-4">{children}</div>
    </section>
  )
}

function Pull({ children }: { children: React.ReactNode }) {
  return (
    <blockquote className="my-4 rounded-lg border-l-4 border-brand-green-700 bg-brand-green-50 px-5 py-4 text-lg italic font-semibold leading-relaxed text-brand-green-900">
      {children}
    </blockquote>
  )
}

function FounderQuote({ children }: { children: React.ReactNode }) {
  return (
    <blockquote className="my-3 rounded-lg border-l-4 border-amber-400 bg-amber-50 px-5 py-4 italic leading-relaxed text-neutral-900">
      &ldquo;{children}&rdquo;
    </blockquote>
  )
}
