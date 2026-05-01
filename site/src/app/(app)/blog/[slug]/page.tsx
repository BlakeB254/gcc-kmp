import { notFound } from 'next/navigation'

export const runtime = 'nodejs'
export const dynamic = 'force-static'
export const revalidate = 3600

const POSTS = ['2026-05-01-issue-01']

export async function generateStaticParams() {
  return POSTS.map((slug) => ({ slug }))
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params
  if (slug !== '2026-05-01-issue-01') return {}
  return {
    title: 'Sixty years and two blocks — Global Community Cleaners Issue #1',
    description:
      "GCC's first newsletter. The 60-year throughline from Dr. King's 1966 Hamlin Avenue apartment to GCC's 2026 Harding Avenue office, the 110+ resident-mapped hotspots in our app, the patterns we're noticing, and how the model works.",
    openGraph: {
      title: 'Sixty years and two blocks — GCC Issue #1',
      description: "GCC's first newsletter. Six blocks from Dr. King's 1966 apartment, with 110+ resident-mapped hotspots.",
      type: 'article',
      publishedTime: '2026-05-01',
      images: ['/newsletter/issue-01/heatmap.png'],
    },
  }
}

export default async function BlogPostPage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  if (slug !== '2026-05-01-issue-01') notFound()

  return <Issue1 />
}

function Issue1() {
  const media = '/newsletter/issue-01'
  return (
    <article className="bg-neutral-50 pb-24 pt-12">
      <div className="mx-auto max-w-3xl px-5">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-700">
            GCC Blog · Issue #1 · The Founder's Letter
          </p>
          <p className="mt-2 text-sm text-neutral-500">May 1, 2026</p>
          <h1 className="mt-6 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            Sixty years and two blocks
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-neutral-600">
            Three things: where we come from, what we already know about our neighborhood, and what we're going to do about it.
          </p>
        </header>

        {/* §1 The history */}
        <Section label="§1 · The history">
          <p>
            On <strong>January 26, 1966</strong>, Dr. Martin Luther King Jr. carried his bags up to a third-floor walk-up at <strong>1550 South Hamlin Avenue</strong> in North Lawndale and started what he called the &ldquo;action phase&rdquo; of the Chicago Freedom Movement &mdash; the campaign that brought him here to focus on housing conditions and economic opportunity in Chicago neighborhoods. The campaign produced the Summit Agreement, which fed directly into the 1968 Fair Housing Act.
          </p>
          <p>
            Today, Global Community Cleaners is headquartered at <strong>1844 South Harding, 2nd Floor</strong>, a six-block walk from the apartment Dr. King moved into.
          </p>
          <Pull>Sixty years. Two blocks of distance. Same neighborhood, same work.</Pull>
        </Section>

        {/* §2 Data we hold */}
        <Section label="§2 · The data we already hold">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            We're not a cleaning service. We're the field-evidence layer for community cleanliness in North Lawndale.
          </h2>
          <p>
            Most organizations talk about a problem. We hold the data on it. The GCC mobile app is live on iOS and Android, and North Lawndale residents have already used it to map &mdash; with photos, GPS, and timestamps &mdash; <strong>110+ trash and dumping hotspots</strong> across the community.
          </p>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`${media}/stats/app-live.png`} alt="The app is live. 110+ hotspots already mapped by the community." className="mt-6 w-full rounded-xl shadow-sm" />
          <div className="mt-5 rounded-xl border border-neutral-200 bg-white p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-neutral-500">Live heatmap · GCC mobile app</p>
            <p className="mt-2 text-sm text-neutral-700">Every red cluster below is a real, photographed hotspot a resident reported. The denser the heat, the more accumulation that block has seen.</p>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={`${media}/heatmap.png`} alt="GCC app heatmap of North Lawndale showing 110+ reported hotspots" className="mx-auto mt-4 max-w-md rounded-lg" />
          </div>
        </Section>

        {/* §3 Conditions */}
        <Section label="§3 · What 110 hotspots actually look like">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            We documented it because reading it doesn't do it justice.
          </h2>
          <p>A small sample from the field. Three of the 110+ photographs residents have logged in the app — all in or immediately around North Lawndale, all captured in the last several weeks.</p>
          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
            {[
              { src: `${media}/condition-1.jpg`, caption: 'Dumped cardboard, residential block.' },
              { src: `${media}/condition-2.jpg`, caption: 'Construction debris on vacant land.' },
              { src: `${media}/condition-3.jpg`, caption: 'Wind-driven accumulation along fenceline.' },
            ].map((p) => (
              <figure key={p.src}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={p.src} alt={p.caption} className="aspect-[3/4] w-full rounded-lg object-cover" />
                <figcaption className="mt-2 text-xs italic text-neutral-500">{p.caption}</figcaption>
              </figure>
            ))}
          </div>
        </Section>

        {/* §4 Patterns */}
        <Section label="§4 · Patterns the data reveals">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            110 photographs add up to a small body of urban evidence. Two patterns jumped out.
          </h2>
          <Callout label="Pattern 1 · Wind-driven fenceline accumulation">
            <p>
              Fencelines bordering vacant fields and parking lots are catching disproportionate trash &mdash; not because residents dump there, but because <strong>wind funnels light debris into them</strong> and nothing breaks the pattern.
            </p>
            <p className="mt-2 text-neutral-600">
              <strong>Long-term fix:</strong> simple windbreak plantings or screen fabric along the worst fencelines, plus a cleanup cadence keyed to wind events. Recoverable infrastructure investment, not a cleanup forever-loop.
            </p>
          </Callout>
          <Callout label="Pattern 2 · Walkway corridors without trash receptacles">
            <p>
              Several major pedestrian corridors in North Lawndale have <strong>no public trash cans for blocks at a time</strong>. Hotspot density along these corridors is measurably higher than in zones with built-in receptacles.
            </p>
            <p className="mt-2 text-neutral-600">
              <strong>Long-term fix:</strong> targeted bin placement at high-foot-traffic intersections &mdash; either a city ask, a property-owner partnership, or a sponsored bin program. We can name the corridors. We have the data.
            </p>
          </Callout>
          <p className="mt-6 italic text-neutral-700">
            This is the real differentiator. We clean the blocks, and we identify what infrastructure could stop the next round of accumulation before it starts.
          </p>
        </Section>

        {/* §5 Why this works */}
        <Section label="§5 · Why this works">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            A randomized citywide trial — the gold standard — proved this model works.
          </h2>
          <p>
            Charles Branas's team at Penn ran a cluster-randomized trial across Philadelphia, comparing <strong>greened lots</strong>, trash-removal-only lots, and untouched controls over 18 months. The results, published in <em>PNAS</em> and <em>JAMA Network Open</em>, are some of the strongest evidence in the urban-policy literature for any intervention at any cost.
          </p>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`${media}/stats/branas-rct.png`} alt="Branas trial results: 29.1% drop in gun assaults, 41.5% fewer depression reports, 68.7% drop for low-income residents" className="mt-6 w-full rounded-xl" />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`${media}/stats/roi.png`} alt="$7.43 in additional municipal tax revenue per public dollar spent on greening" className="mt-4 w-full rounded-xl" />
          <p className="mt-6">
            The newer research goes further. A 2019 meta-analysis in <em>Social Science &amp; Medicine</em> found that the original broken-windows hypothesis works mostly as a downstream signal of <strong>collective efficacy</strong> &mdash; whether neighbors feel any agency over their block. Robert Sampson's Chicago research showed that neighborhoods scoring high on collective efficacy run roughly <strong>40% lower</strong> crime rates, even when income, race, and visible disorder look similar on paper.
          </p>
          <p className="mt-4 font-semibold text-neutral-900">
            The lesson, repeated across every modern study: invest in the people on the block, and the block follows.
          </p>
        </Section>

        {/* §6 North Lawndale data */}
        <Section label="§6 · The neighborhood we're working in">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The municipal data on North Lawndale isn't subtle.
          </h2>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`${media}/stats/life-expectancy-gap.png`} alt="20.7-year life-expectancy gap: 87.3 in The Loop vs 66.6 in West Garfield Park" className="mt-6 w-full rounded-xl" />
          <p className="mt-4">
            Black Chicagoans died <strong>10.6 years earlier</strong> than non-Black Chicagoans in 2023 (CDPH). North Lawndale's per-capita violent-crime rate runs roughly <strong>3&times; the citywide rate</strong>; it sits on the city's official list of areas &ldquo;most affected by violence&rdquo; (UIC Great Cities Institute, 2022). This is the gap a $100 block fee, paid to the resident on the block, is built to start closing.
          </p>
        </Section>

        {/* §7 Founder */}
        <Section label="§7 · Meet the founder">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Shaquille Tate built it because he was tired of seeing it.
          </h2>
          <p>
            Born at Mt. Sinai Hospital in North Lawndale. Raised on the blocks running from <strong>15th Street to Ridgeway</strong>. Drives Divvy across the entire city for his day job &mdash; which means he sees what every Chicago neighborhood looks like from the curb. Asked on a 2026 radio interview how the idea found him, he answered:
          </p>
          <FounderQuote>I had to come back to the North Lawndale community, and I'm walking past abandoned houses &mdash; boards on the windows, broken windows, trash everywhere on the floor.</FounderQuote>
          <FounderQuote>What if somebody could get all these kids to clean up their block? And if they were to clean up their block, to get paid to clean up their block.</FounderQuote>
          <FounderQuote>I'm employing elders and I'm employing youth and I'm paying them a hundred dollars to clean up their blocks. I want to start with a 10-block radius… This is more than just picking up trash.</FounderQuote>
          <p className="mt-6">
            He's right. It is about cleaning, but it's also about cash, dignity, ownership, and the simple fact that <strong>the people best equipped to take care of a block are the people who live on it</strong> &mdash; if someone is willing to pay them like the work matters. Because it does.
          </p>
        </Section>

        {/* §8 Model */}
        <Section label="§8 · How the model works">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">Plain on purpose.</h2>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`${media}/stats/block-model.png`} alt="$100 cleans one block. 100% of it goes to the resident doing the work." className="mt-6 w-full rounded-xl" />
          <p className="mt-6">
            One participant per block. Elders <em>and</em> youth &mdash; the model is intergenerational by design. Before-and-after photos to every sponsor; block status logged in our system. The same measurement discipline that turned greening from a hunch into peer-reviewed practice.
          </p>
          <p className="mt-4">
            A <strong>$100 block sponsorship</strong> gets your name on our public sponsor wall. A <strong>$2,000+ sponsorship</strong> unlocks the recognition tier &mdash; name on the wall, digital certificate of appreciation, 501(c)(3) tax receipt, monthly newsletter feature, and social-media recognition across our channels.
          </p>
        </Section>

        {/* §9 The ask */}
        <Section label="§9 · The ask">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            If this resonates, here is exactly what to do with that.
          </h2>
          <div className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <a
              href="/sponsor"
              className="rounded-lg bg-emerald-700 px-5 py-4 text-center text-base font-semibold text-white hover:bg-emerald-800"
            >
              Sponsor a block — $100
            </a>
            <a
              href="/sponsor?amount=2000"
              className="rounded-lg bg-neutral-900 px-5 py-4 text-center text-base font-semibold text-white hover:bg-neutral-800"
            >
              Recognized sponsor — $2,000
            </a>
          </div>
          <p className="mt-4 text-sm text-neutral-700">
            <strong>$100</strong> = one cleanup, one paid resident, one before-and-after photo set in your inbox.<br />
            <strong>$2,000</strong> = your name on our public wall, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition.
          </p>
        </Section>

        {/* §10 What's next */}
        <Section label="§10 · What's next">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Right now we're preparing the first 20 cleanups.
          </h2>
          <p>
            Twenty blocks &mdash; twenty paid residents &mdash; <strong>20% of our way to the 100-block goal</strong> we set for the North Lawndale pilot. One hundred blocks cleaned, one hundred community members earning real money on their own street. That's the floor, not the ceiling.
          </p>
          <div className="mt-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-emerald-800">Goal · North Lawndale pilot</p>
            <p className="mt-1 text-lg font-bold text-neutral-900">100 blocks cleaned · 100 community members paid</p>
            <div className="mt-3 flex h-5 overflow-hidden rounded-full border border-emerald-200 bg-white">
              <div className="flex items-center justify-center bg-emerald-700 text-xs font-bold text-white" style={{ width: '20%' }}>
                20%
              </div>
            </div>
            <p className="mt-2 text-xs text-neutral-500">First 20 cleanups: in preparation. Sponsor a block to put it on a resident's street.</p>
          </div>
          <p className="mt-6">
            <strong>In June</strong> we publish the first block-by-block before-and-after report. <strong>In July</strong> we feature the residents who claimed those first cleanups: names, photos, blocks.
          </p>
          <p className="mt-4 font-semibold">In the meantime — Dr. King's apartment is six blocks away. The work continues.</p>
          <p className="mt-2 italic text-neutral-500">— The team at Global Community Cleaners NFP</p>
        </Section>

        {/* Footer / share */}
        <div className="mt-16 border-t border-neutral-200 pt-8 text-center text-sm text-neutral-500">
          <p>
            <a className="text-emerald-700 underline" href="/sponsor">
              Sponsor a block →
            </a>
            {' · '}
            <a className="text-emerald-700 underline" href="/">
              GCC home
            </a>
          </p>
          <p className="mt-3 text-xs">
            Global Community Cleaners NFP · 1844 S. Harding Ave, 2nd Floor · Chicago, IL 60623 ·
            501(c)(3) tax-exempt · EIN 39-3553882
          </p>
        </div>
      </div>
    </article>
  )
}

function Section({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <section className="mt-16 border-t-2 border-neutral-200 pt-12 text-base leading-relaxed text-neutral-700">
      <p className="mb-4 text-xs font-bold uppercase tracking-[0.18em] text-emerald-700">
        {label}
      </p>
      <div className="space-y-4">{children}</div>
    </section>
  )
}

function Pull({ children }: { children: React.ReactNode }) {
  return (
    <blockquote className="my-4 rounded-lg border-l-4 border-emerald-700 bg-emerald-50 px-5 py-4 text-lg italic font-semibold leading-relaxed text-emerald-900">
      {children}
    </blockquote>
  )
}

function Callout({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mt-4 rounded-lg border-l-4 border-amber-400 bg-amber-50 px-5 py-4 text-sm leading-relaxed text-neutral-900">
      <p className="mb-2 text-xs font-bold uppercase tracking-widest text-amber-900">{label}</p>
      {children}
    </div>
  )
}

function FounderQuote({ children }: { children: React.ReactNode }) {
  return (
    <blockquote className="my-3 rounded-lg border-l-4 border-amber-400 bg-amber-50 px-5 py-4 italic leading-relaxed text-neutral-900">
      &ldquo;{children}&rdquo;
    </blockquote>
  )
}
