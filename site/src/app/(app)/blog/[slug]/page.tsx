import { notFound } from 'next/navigation'

export const runtime = 'nodejs'
// Dynamic so the scheduled-publish gate (see SCHEDULE below) is evaluated live
// on every request — the post flips from 404 to live exactly at its publishAt
// moment, with no stale-cache window.
export const dynamic = 'force-dynamic'

// Scheduled-publish gate ----------------------------------------------------
// A post is hidden until its publishAt moment passes. Issue #2 is set to drop
// at 8:00 AM Central on 2026-06-01 (-05:00 = CDT). Past-dated posts are always
// live. Gated purely on the wall clock — no NODE_ENV dependency (an earlier
// `process.env.NODE_ENV !== 'production'` dev-bypass leaked into the live
// runtime and published the post early). To preview an unpublished draft
// locally, temporarily move its SCHEDULE date into the past.
const SCHEDULE: Record<string, string> = {
  '2026-05-01-issue-01': '2026-05-01T00:00:00-05:00',
  '2026-06-01-issue-02': '2026-06-01T08:00:00-05:00',
  '2026-07-03-issue-03': '2026-07-03T08:00:00-05:00',
  '2026-07-31-issue-04': '2026-07-31T08:00:00-05:00',
  '2026-08-19-issue-05': '2026-08-19T08:00:00-05:00',
}

function isPublished(slug: string): boolean {
  const at = SCHEDULE[slug]
  if (!at) return false
  return Date.now() >= new Date(at).getTime()
}

const POSTS = [
  '2026-05-01-issue-01',
  '2026-06-01-issue-02',
  '2026-07-03-issue-03',
  '2026-07-31-issue-04',
  '2026-08-19-issue-05',
]

export async function generateStaticParams() {
  return POSTS.map((slug) => ({ slug }))
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params
  if (slug === '2026-05-01-issue-01') {
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
  if (slug === '2026-06-01-issue-02') {
    if (!isPublished(slug)) return {} // don't advertise an unpublished post in <head>
    return {
      title: 'Reading the map — Global Community Cleaners Issue #2',
      description:
        "GCC's Hotspot Report. A month deeper into the field evidence behind 110+ resident-mapped hotspots in North Lawndale: the kinds of blight we keep finding, the corridors we can already name, a shout-out to Fields of Dreams Chicago, and an open invitation to local businesses to back our points economy.",
      openGraph: {
        title: 'Reading the map — GCC Issue #2 · The Hotspot Report',
        description:
          "What 110+ resident-mapped hotspots tell us, the corridors we can name, and how Fields of Dreams Chicago equipped our first crews.",
        type: 'article',
        publishedTime: '2026-06-01',
        images: ['/newsletter/issue-02/heatmap.png'],
      },
    }
  }
  if (slug === '2026-07-03-issue-03') {
    if (!isPublished(slug)) return {} // don't advertise an unpublished post in <head>
    return {
      title: 'Boots on the ground — Global Community Cleaners Issue #3',
      description:
        "GCC's third newsletter. A redesigned site, a live iOS scanning feature, a $2,000 gift from UIC toward our 100-block pilot goal, the first North Lawndale cleanups happening July 5-6, a real scan-powered heatmap, and a look at the points economy taking shape.",
      openGraph: {
        title: 'Boots on the ground — GCC Issue #3',
        description:
          "UIC backs our pilot goal, the first North Lawndale cleanups land July 5-6, and the map starts running on real scan data.",
        type: 'article',
        publishedTime: '2026-07-03',
        images: ['/newsletter/issue-02/heatmap.png'],
      },
    }
  }
  if (slug === '2026-07-31-issue-04') {
    if (!isPublished(slug)) return {} // don't advertise an unpublished post in <head>
    return {
      title: 'The scan system grows teeth — Global Community Cleaners Issue #4',
      description:
        "GCC's fourth newsletter. The first North Lawndale cleanups ran July 5-6, UIC's $2,000 gift came through its Office of Community Collaboration, the iOS env-scan overhaul shipped across TestFlight builds 165-178, and the scan-powered heatmap sharpens with every session.",
      openGraph: {
        title: 'The scan system grows teeth — GCC Issue #4',
        description:
          "The first cleanups are on the books, UIC's gift came through the office built for this neighborhood, and the scan system grew teeth over fourteen TestFlight builds.",
        type: 'article',
        publishedTime: '2026-07-31',
        images: ['/newsletter/issue-02/heatmap.png'],
      },
    }
  }
  if (slug === '2026-08-19-issue-05') {
    if (!isPublished(slug)) return {} // don't advertise an unpublished post in <head>
    return {
      title: 'From cleaning blocks to mapping them in 3D — Global Community Cleaners Issue #5',
      description:
        "GCC's fifth newsletter. 20 of 100 blocks cleaned and $2,000 paid out to North Lawndale residents, 158 scan sessions captured, LiDAR depth + camera poses + GPS fusing into per-block 3D, a litter classifier in training, iOS v1.0.8 in TestFlight, and the new honor wall.",
      openGraph: {
        title: 'From cleaning blocks to mapping them in 3D — GCC Issue #5',
        description:
          '20 blocks cleaned, $2,000 paid to residents, and 158 scan sessions fusing into a digital twin of the neighborhood — built for accountability, not for show.',
        type: 'article',
        publishedTime: '2026-08-19',
        images: ['/newsletter/issue-02/heatmap.png'],
      },
    }
  }
  return {}
}

export default async function BlogPostPage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  if (!POSTS.includes(slug) || !isPublished(slug)) notFound()

  if (slug === '2026-08-19-issue-05') return <Issue5 />
  if (slug === '2026-07-31-issue-04') return <Issue4 />
  if (slug === '2026-07-03-issue-03') return <Issue3 />
  if (slug === '2026-06-01-issue-02') return <Issue2 />
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
            Most organizations talk about a problem. We hold the data on it. The GCC mobile app is live on iOS (Android version in development), and North Lawndale residents have already used it to map &mdash; with photos, GPS, and timestamps &mdash; <strong>110+ trash and dumping hotspots</strong> across the community.
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

        {/* §9 Anyone, anywhere can participate */}
        <Section label="§9 · Anyone, anywhere can participate">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            You don't have to live in Chicago to put a hotspot on the map.
          </h2>
          <p>
            The GCC app is global. Any user, anywhere in the world, can <strong>spot</strong> illegal dumping, blight, or unsafe conditions in their own neighborhood, photograph it, and add it to the map. Other users earn points by <strong>verifying</strong> a spot is real, still there, or has been resolved. The data layer compounds with every contribution.
          </p>
          <p>
            We're building this into a <strong>points system worth participating in</strong>. We are actively working with local businesses to back the points with rewards our community actually wants:
          </p>
          <div className="mt-4 rounded-lg border border-sky-200 bg-sky-50 px-5 py-4">
            <p className="mb-2 text-xs font-bold uppercase tracking-widest text-sky-900">
              Reward types in development
            </p>
            <p className="text-sm leading-relaxed text-neutral-900">
              🏟️ Sports &amp; concert tickets · 🎁 Gift cards from neighborhood businesses · 🍽️ Restaurant credits · 🛍️ Discounts from partner retailers · 💎 Redeemable points across our internal network
            </p>
          </div>
          <div className="mt-5 rounded-xl bg-gradient-to-br from-emerald-900 to-emerald-700 px-7 py-6 text-white shadow-lg">
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-amber-300">
              Local businesses, this is your invitation
            </p>
            <p className="text-base font-semibold leading-snug">
              If you can put real value behind these points &mdash; tickets, gift cards, table reservations, in-store credits &mdash; we will put your brand directly in front of the residents already cleaning their own streets.
            </p>
            <p className="mt-2 text-sm leading-relaxed opacity-90">
              It's the cleanest community-affairs ROI in Chicago: documented impact, photographable proof, audience that lives right where you sell. Email{' '}
              <a className="text-amber-300 underline" href="mailto:hello@globalcommunitycleaners.org?subject=Partner%20on%20points">
                hello@globalcommunitycleaners.org
              </a>{' '}
              with the subject line <strong>"Partner on points"</strong>.
            </p>
          </div>
        </Section>

        {/* §10 The ask */}
        <Section label="§10 · The ask">
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

        {/* §11 What's next */}
        <Section label="§11 · What's next">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Right now we're lining up the first 20 cleanups.
          </h2>
          <p>
            Twenty blocks &mdash; twenty paid residents &mdash; <strong>20% of our way to the 100-block goal</strong> we set for the North Lawndale pilot. One hundred blocks cleaned, one hundred community members earning real money on their own street. That's the floor, not the ceiling.
          </p>
          <p className="italic text-neutral-500">
            The first 20 are pending a soft commitment we're closing with a local partner; we'll name them in the next issue once the funding has cleared and the work is underway. In the meantime, every $100 block sponsorship we receive between now and then funds an additional cleanup on top of those 20.
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

function Issue2() {
  const media = '/newsletter/issue-02'
  return (
    <article className="bg-neutral-50 pb-24 pt-12">
      <div className="mx-auto max-w-3xl px-5">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-700">
            GCC Blog · Issue #2 · The Hotspot Report
          </p>
          <p className="mt-2 text-sm text-neutral-500">June 1, 2026</p>
          <h1 className="mt-6 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            Reading the map
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-neutral-600">
            Last month we told you why we exist. This month we go a layer deeper into the evidence — what 110+ resident-mapped hotspots actually reveal, the blocks we can already name, and the neighbor who equipped our first crews.
          </p>
        </header>

        {/* §1 Where we are */}
        <Section label="§1 · Where we left off">
          <p>
            In{' '}
            <a className="font-semibold text-emerald-700 underline" href="/blog/2026-05-01-issue-01">
              Issue #1
            </a>
            , we laid out the thesis: Global Community Cleaners isn&rsquo;t a cleaning service &mdash; it&rsquo;s the <strong>field-evidence layer</strong> for community cleanliness in North Lawndale, six blocks from the apartment Dr.&nbsp;King moved into in 1966. We pay the resident on the block to clean it, and we document every inch of it.
          </p>
          <p>
            One month on, the map has kept growing. So this issue is about the part most organizations skip: <strong>actually reading the data</strong> &mdash; and being honest about what it does and doesn&rsquo;t yet tell us.
          </p>
          <Pull>You can&rsquo;t fix a block you haven&rsquo;t looked at honestly. So we looked.</Pull>
        </Section>

        {/* §2 The map one month on */}
        <Section label="§2 · The map, one month on">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            110+ hotspots, every one photographed, GPS-tagged, and timestamped by a resident.
          </h2>
          <p>
            This is not a survey or an estimate. Every red cluster below is a real location someone in the community walked up to, photographed, and logged in the GCC app. The denser the heat, the more accumulation that stretch has seen.
          </p>
          <div className="mt-5 rounded-xl border border-neutral-200 bg-white p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-neutral-500">Live heatmap · GCC mobile app</p>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={`${media}/heatmap.png`} alt="GCC app heatmap of North Lawndale showing 110+ reported hotspots" className="mx-auto mt-4 max-w-md rounded-lg" />
            <p className="mt-3 text-center text-xs italic text-neutral-500">
              The map is open and growing. See it live on the{' '}
              <a className="text-emerald-700 underline" href="/bounties">bounties map</a>.
            </p>
          </div>
        </Section>

        {/* §3 The four kinds of blight */}
        <Section label="§3 · Four kinds of blight">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            When you photograph a hundred hotspots, they stop looking random. They sort into types.
          </h2>
          <p>
            Reading the reports back, almost everything residents have logged falls into one of four categories. Naming them matters, because each one has a <em>different</em> long-term fix &mdash; and only one of them is actually solved by a broom.
          </p>
          <div className="mt-6 space-y-3">
            <TypeCard n="01" name="Illegal dumping" desc="Deliberate drop-offs — cardboard, bagged household trash, mattresses — on residential blocks and parkways, usually overnight. The single most reported type." />
            <TypeCard n="02" name="Construction & demo debris" desc="Bricks, drywall, and rubble left on vacant lots and teardown sites. Heavy, slow to clear, and a magnet for more dumping once it sits." />
            <TypeCard n="03" name="Wind-driven accumulation" desc="Light litter funneled by wind into fencelines bordering vacant fields and parking lots. Nobody dumped it there — the wind delivered it, and nothing breaks the pattern." />
            <TypeCard n="04" name="No-receptacle corridors" desc="Pedestrian stretches with no public trash can for blocks at a time. Foot traffic plus nowhere to throw anything equals predictable build-up." />
          </div>
          <p className="mt-6 text-sm italic text-neutral-500">
            A small sample from the field — three of the 110+ photographs residents have logged, all in or around North Lawndale.
          </p>
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
            {[
              { src: `${media}/condition-1.jpg`, caption: 'Type 01 — dumped cardboard, residential block.' },
              { src: `${media}/condition-2.jpg`, caption: 'Type 02 — construction debris on vacant land.' },
              { src: `${media}/condition-3.jpg`, caption: 'Type 03 — wind-driven accumulation along a fenceline.' },
            ].map((p) => (
              <figure key={p.src}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={p.src} alt={p.caption} className="aspect-[3/4] w-full rounded-lg object-cover" />
                <figcaption className="mt-2 text-xs italic text-neutral-500">{p.caption}</figcaption>
              </figure>
            ))}
          </div>
        </Section>

        {/* §4 Corridors we can name */}
        <Section label="§4 · The corridors we can already name">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Two of those four types aren&rsquo;t cleaning problems at all. They&rsquo;re infrastructure problems we can point to on a map.
          </h2>
          <Callout label="Pattern 1 · Wind-driven fencelines">
            <p>
              Fencelines along vacant fields and parking lots catch disproportionate trash &mdash; not from dumping, but because <strong>wind funnels light debris into them</strong> and nothing stops it. Sweep it today, it&rsquo;s back next windy week.
            </p>
            <p className="mt-2 text-neutral-600">
              <strong>Long-term fix:</strong> simple windbreak plantings or screen fabric on the worst fencelines, plus a cleanup cadence keyed to wind events. A one-time investment, not a forever-loop.
            </p>
          </Callout>
          <Callout label="Pattern 2 · Walkway corridors without receptacles">
            <p>
              Several major pedestrian corridors run <strong>blocks at a time with no public trash can</strong>. Hotspot density along them is visibly higher than on comparable stretches that have bins.
            </p>
            <p className="mt-2 text-neutral-600">
              <strong>Long-term fix:</strong> targeted bin placement at high-foot-traffic intersections &mdash; a city ask, a property-owner partnership, or a sponsored-bin program. <strong>We can name the corridors. We have the data.</strong>
            </p>
          </Callout>
          <p className="mt-6 italic text-neutral-700">
            This is the real differentiator. We clean the block today, and we identify the infrastructure that would stop the next round before it starts.
          </p>
        </Section>

        {/* §5 Why this matters — the evidence base */}
        <Section label="§5 · Why the evidence matters">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            A randomized citywide trial already proved that cleaning blocks changes neighborhoods.
          </h2>
          <p>
            Charles Branas&rsquo;s team at Penn ran a cluster-randomized trial across Philadelphia &mdash; the gold standard &mdash; comparing greened lots, trash-removal-only lots, and untouched controls. Published in <em>PNAS</em> and <em>JAMA Network&nbsp;Open</em>, it found a <strong>29% drop in gun assaults</strong> around treated lots and large reductions in residents&rsquo; reported depression, with the strongest effects for low-income residents.
          </p>
          <p>
            Robert Sampson&rsquo;s Chicago research adds the why: neighborhoods high in <strong>collective efficacy</strong> &mdash; whether neighbors feel any agency over their block &mdash; run roughly <strong>40% lower</strong> crime rates even when income and visible disorder look identical on paper. Our map is collective efficacy made literal: a hundred neighbors deciding their block is worth documenting.
          </p>
          <p className="mt-2 font-semibold text-neutral-900">
            Invest in the people on the block, and the block follows. Our job is to give those people the data, the tools, and the paycheck.
          </p>
        </Section>

        {/* §6 Fields of Dreams Chicago shout-out */}
        <Section label="§6 · The tools in our hands came from a neighbor">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Our first crews will work with shovels, brooms, and gloves donated by{' '}
            <span className="text-emerald-800">Fields of Dreams Chicago</span>.
          </h2>
          <p>
            On <strong>May 13</strong>, Fields of Dreams Chicago left GCC&rsquo;s <strong>first in-kind donation ever</strong> at the UIC Neighborhood Center: 3 boxes of gloves, 3 shovels, 4 stainless dustpan-and-broom sets, 2 push brooms, and a 30-pack of metal pickup sticks &mdash; roughly <strong>$500</strong> of the exact equipment our crews need to do the work. Founder <strong>James Fields</strong> was deliberate about naming it an in-kind gift and even coached us on building the donor records a young nonprofit lives or dies by. That generosity is a gift on top of the gift.
          </p>

          <div className="mt-6 rounded-2xl border border-emerald-200 bg-white p-6 shadow-sm">
            <p className="text-xs font-bold uppercase tracking-widest text-emerald-700">Neighbor spotlight</p>
            <h3 className="mt-2 font-serif text-2xl font-bold text-neutral-900">Fields of Dreams Chicago</h3>
            <p className="mt-3 text-sm leading-relaxed text-neutral-700">
              Founded in 2024 by James Fields, Fields of Dreams is a North Lawndale youth-empowerment nonprofit serving Black and Latinx young people ages <strong>11&ndash;15</strong> through mentorship, wellness, and community service. Its signature <strong>Neighborhood Nurturers</strong> program runs year-round Saturday sessions at North Lawndale College Prep &mdash; a shared meal, team-building, and youth-led service projects &mdash; and its summer <strong>DreamBuilders Camp</strong> centers environmental stewardship. Youth even earn stipends for showing up and leading. They split their members into <em>dream builders</em> (the youth) and <em>vision builders</em> (the mentors), all working toward what they call a beloved community &ldquo;infused with love and joy.&rdquo;
            </p>
            <p className="mt-3 text-sm leading-relaxed text-neutral-700">
              In other words: while GCC pays this generation to steward their blocks, Fields of Dreams is raising the <em>next</em> one to do the same. Same neighborhood, same belief &mdash; that the people on the block are the ones to care for it.
            </p>
            <a
              href="https://www.fieldsofdreamschicago.org"
              target="_blank"
              rel="noopener noreferrer"
              className="mt-4 inline-block rounded-lg bg-emerald-700 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-800"
            >
              Visit Fields of Dreams Chicago →
            </a>
          </div>

          <Pull>
            The first shovel that breaks ground on a GCC block was handed to us by an organization teaching kids that the block is theirs to love. We couldn&rsquo;t have scripted a better way to start.
          </Pull>
        </Section>

        {/* §7 The points economy — business invitation */}
        <Section label="§7 · The points economy — an open invitation">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The map gets stronger every time someone contributes. We want to make contributing worth it.
          </h2>
          <p>
            The GCC app is global. Anyone, anywhere, can <strong>spot</strong> illegal dumping or blight in their neighborhood, photograph it, and add it to the map &mdash; and other users earn points by <strong>verifying</strong> a spot is real, still there, or resolved. The data layer compounds with every contribution.
          </p>
          <p>
            We&rsquo;re building this into a points system worth participating in, and we&rsquo;re lining up local businesses to back the points with rewards our community actually wants:
          </p>
          <div className="mt-4 rounded-lg border border-sky-200 bg-sky-50 px-5 py-4">
            <p className="mb-2 text-xs font-bold uppercase tracking-widest text-sky-900">Reward types in development</p>
            <p className="text-sm leading-relaxed text-neutral-900">
              🏟️ Sports &amp; concert tickets · 🎁 Gift cards from neighborhood businesses · 🍽️ Restaurant credits · 🛍️ Discounts from partner retailers · 💎 Redeemable points across our internal network
            </p>
          </div>
          <div className="mt-5 rounded-xl bg-gradient-to-br from-emerald-900 to-emerald-700 px-7 py-6 text-white shadow-lg">
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-amber-300">
              Chicago businesses, this is your invitation
            </p>
            <p className="text-base font-semibold leading-snug">
              If you can put real value behind these points &mdash; tickets, gift cards, table reservations, in-store credits &mdash; we&rsquo;ll put your brand directly in front of the residents already cleaning their own streets.
            </p>
            <p className="mt-2 text-sm leading-relaxed opacity-90">
              It&rsquo;s the cleanest community-affairs ROI in the city: documented impact, photographable proof, and an audience that lives right where you sell. Fields of Dreams Chicago showed what one intentional neighbor can do with a $500 gift. Imagine what your brand could do. Email{' '}
              <a className="text-amber-300 underline" href="mailto:hello@globalcommunitycleaners.org?subject=Partner%20on%20points">
                hello@globalcommunitycleaners.org
              </a>{' '}
              with the subject line <strong>&ldquo;Partner on points&rdquo;</strong>.
            </p>
          </div>
        </Section>

        {/* §8 The ask */}
        <Section label="§8 · The ask">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Equipment is in hand. Now we need the blocks funded.
          </h2>
          <p>
            Thanks to Fields of Dreams, our first crews are equipped. A <strong>$100 block sponsorship</strong> puts that equipment in a resident&rsquo;s hands, pays them for the work, and sends a before-and-after photo set to your inbox. 100% of the block fee goes to the person doing the work.
          </p>
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

        {/* §9 What's next */}
        <Section label="§9 · What's next">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            We&rsquo;re closing the funding to put those first crews on the street.
          </h2>
          <p>
            Our goal for the North Lawndale pilot hasn&rsquo;t changed: <strong>100 blocks cleaned, 100 community members paid</strong> on their own streets. The equipment is here. The map is mapped. What stands between today and the first before-and-after report is funded blocks &mdash; and every $100 sponsorship moves one resident from the waitlist to the work.
          </p>
          <div className="mt-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-emerald-800">Goal · North Lawndale pilot</p>
            <p className="mt-1 text-lg font-bold text-neutral-900">100 blocks cleaned · 100 community members paid</p>
            <div className="mt-3 flex h-5 overflow-hidden rounded-full border border-emerald-200 bg-white">
              <div className="flex items-center justify-center bg-emerald-700 text-xs font-bold text-white" style={{ width: '20%' }}>
                20%
              </div>
            </div>
            <p className="mt-2 text-xs text-neutral-500">First cleanups: equipped and in preparation. Sponsor a block to put one on a resident&rsquo;s street.</p>
          </div>
          <p className="mt-6">
            When those blocks are funded and cleaned, <strong>the next issue is the one we&rsquo;ve been promising</strong>: the first block-by-block before-and-after report, with the residents who claimed them &mdash; names, photos, blocks.
          </p>
          <p className="mt-4 font-semibold">Dr. King&rsquo;s apartment is six blocks away. The work continues.</p>
          <p className="mt-2 italic text-neutral-500">— The team at Global Community Cleaners NFP</p>
        </Section>

        {/* Footer / share */}
        <div className="mt-16 border-t border-neutral-200 pt-8 text-center text-sm text-neutral-500">
          <p>
            <a className="text-emerald-700 underline" href="/sponsor">
              Sponsor a block →
            </a>
            {' · '}
            <a className="text-emerald-700 underline" href="/blog/2026-05-01-issue-01">
              Read Issue #1
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

function Issue3() {
  const media = '/newsletter/issue-02'
  return (
    <article className="bg-neutral-50 pb-24 pt-12">
      <div className="mx-auto max-w-3xl px-5">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-700">
            GCC Blog · Issue #3 · Boots on the Ground
          </p>
          <p className="mt-2 text-sm text-neutral-500">July 3, 2026</p>
          <h1 className="mt-6 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            Boots on the ground
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-neutral-600">
            A cleaner front door, a gift from a university that studies this exact problem, and a date on the calendar: our first crews hit North Lawndale streets this week.
          </p>
        </header>

        {/* §1 Where we left off */}
        <Section label="§1 · Where we left off">
          <p>
            In{' '}
            <a className="font-semibold text-emerald-700 underline" href="/blog/2026-06-01-issue-02">
              Issue #2
            </a>
            , the map had 110+ hotspots, the equipment was in hand thanks to Fields of Dreams Chicago, and we were closing the funding to put the first crews on the street. Three things changed since: the site got a real front door, the app grew a new set of eyes, and a university put real money behind the goal.
          </p>
          <Pull>Equipment was never the hard part. Funded blocks and a first date were. We have both now.</Pull>
        </Section>

        {/* §2 Site + app */}
        <Section label="§2 · A cleaner front door">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The site got easier to read. The app got a new way to see the block.
          </h2>
          <p>
            We rebuilt the homepage and navigation so the site says what we do in the first five seconds instead of the first scroll. Same organization, same mission — just less digging to find the part you came for.
          </p>
          <p>
            On the app side: the GCC scanning feature is live on iOS today, letting you walk a block and capture its condition as you go rather than logging one spot at a time. <strong>Android is coming soon</strong> to Google Play — we know a large share of North Lawndale carries Android, and closing that gap is one of our top priorities this summer.
          </p>
        </Section>

        {/* §3 The UIC gift */}
        <Section label="§3 · A university backs the goal">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The University of Illinois Chicago gave $2,000 toward our 100-block pilot.
          </h2>
          <p>
            This is the headline of this issue. <strong>UIC has donated $2,000</strong> toward Global Community Cleaners&rsquo; pilot goal of cleaning 100 blocks and creating paid work for local residents in North Lawndale. UIC&rsquo;s own Great Cities Institute is one of the sources we cite when we talk about the conditions here &mdash; a university that studies this neighborhood&rsquo;s data is now funding blocks in it.
          </p>
          <p>
            To be precise about what this gift is and isn&rsquo;t: it&rsquo;s money allocated toward the 100-block goal, today, before the first shovel touches ground on the blocks it funds. We&rsquo;ll report back with the before-and-after photos and the names of the residents it puts to work, the same way we do for every sponsor &mdash; once that work is actually done.
          </p>
          <div className="mt-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-emerald-800">Goal · North Lawndale pilot</p>
            <p className="mt-1 text-lg font-bold text-neutral-900">100 blocks cleaned · 100 community members paid</p>
            <div className="mt-3 flex h-5 overflow-hidden rounded-full border border-emerald-200 bg-white">
              <div className="flex items-center justify-center bg-emerald-700 text-xs font-bold text-white" style={{ width: '20%' }}>
                20%
              </div>
            </div>
            <p className="mt-2 text-xs text-neutral-500">20 blocks funded toward the 100-block goal, UIC&rsquo;s gift among them.</p>
          </div>
        </Section>

        {/* §4 North Lawndale cleanups */}
        <Section label="§4 · The first cleanups land this week">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            July 5 and 6, right after the holiday, our first crews work North Lawndale blocks.
          </h2>
          <p>
            These are our <strong>alpha cleanups</strong> &mdash; the first time real residents run the full app-guided route-fulfillment process on real blocks, start to finish. We&rsquo;re treating both days as a structured test, not just a launch: watching where the app flow is smooth, where it&rsquo;s clunky, and listening hard to what the residents doing the work tell us about their experience.
          </p>
          <p>
            That feedback is the point. We are not rolling the <strong>$100-per-block</strong> route mechanism out to more communities until we&rsquo;ve tightened it against what we learn from these two days.
          </p>
          <Pull>If you worked one of the first two blocks and have thoughts on what to fix, we want to hear all of it &mdash; the app, the payout, the instructions, anything.</Pull>
        </Section>

        {/* §5 The map gets sharper */}
        <Section label="§5 · The map gets sharper">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The heatmap now runs on real scan data, and it&rsquo;s about to look a lot more accurate.
          </h2>
          <p>
            Every scan a user logs &mdash; whether it&rsquo;s a full block walk or a single spot report &mdash; feeds a live cleanliness heatmap of North Lawndale. Zoomed out, you see the neighborhood-level picture: where conditions cluster, where they don&rsquo;t.
          </p>
          <p>
            Zoomed in is where we&rsquo;re upgrading. We&rsquo;re migrating close-zoom rendering away from generic dots toward a visual that actually represents what a block looks like &mdash; density and severity of reported conditions, not just a pin count. This is the part of GCC that isn&rsquo;t a cleaning service at all: it&rsquo;s the data infrastructure for knowing what &ldquo;clean&rdquo; and &ldquo;not clean&rdquo; actually look like, block by block, backed by photos a neighbor took.
          </p>
        </Section>

        {/* §6 Gamification roadmap */}
        <Section label="§6 · Building an economy worth participating in">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The next reward layer is about the areas nobody has looked at yet.
          </h2>
          <p>
            We&rsquo;re adding scans of <strong>never-before-scanned areas</strong>, and areas that haven&rsquo;t been scanned in the <strong>last 24 hours</strong>, into the points system &mdash; rewarding the &ldquo;observe and report&rdquo; side of the app the same way we already reward cleanup work. We&rsquo;re still working out the exact numbers, and we&rsquo;re still looking for the right sponsors to back this specific mechanic &mdash; if that&rsquo;s you, see the invitation below. And if you&rsquo;re a user: tell us what rewards would actually make you want to go scan a block nobody&rsquo;s looked at yet. We&rsquo;re building this for you, so we want your answer before we lock it in.
          </p>
          <p>
            A few other pieces of the economy, already live or in progress:
          </p>
          <div className="mt-4 rounded-lg border border-sky-200 bg-sky-50 px-5 py-4">
            <p className="mb-2 text-xs font-bold uppercase tracking-widest text-sky-900">How points work today &mdash; and where they&rsquo;re headed</p>
            <ul className="space-y-2 text-sm leading-relaxed text-neutral-900">
              <li><strong>Self-initiated cleanups earn points.</strong> Spot something, clean it yourself, log it &mdash; that&rsquo;s the core loop, and we want to keep incentivizing it.</li>
              <li><strong>Anyone can post a paid bounty</strong> on a mess they want gone, and a worker can claim it for cash.</li>
              <li><strong>Bounty pay and points aren&rsquo;t double-dipped the same way.</strong> If you complete a paid bounty, you still earn some points for the work &mdash; just less than you&rsquo;d earn cleaning the same mess for free. Free cleanups are the highest-points path on purpose.</li>
              <li><strong>A full-lifecycle bonus is in the works</strong> &mdash; extra points for observing a mess, recording it, cleaning it, and verifying the clean result, all in one session. The idea is that closing the whole loop yourself should pay out more than the sum of the individual steps, not just add them up. We&rsquo;re still finalizing the numbers and will publish them once they&rsquo;re set.</li>
            </ul>
          </div>
          <div className="mt-5 rounded-xl bg-gradient-to-br from-emerald-900 to-emerald-700 px-7 py-6 text-white shadow-lg">
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-amber-300">
              Sponsors, this ask is specific
            </p>
            <p className="text-base font-semibold leading-snug">
              We&rsquo;re looking for a sponsor to back the scan-and-discover side of the points economy &mdash; rewarding residents for mapping blocks nobody&rsquo;s looked at yet.
            </p>
            <p className="mt-2 text-sm leading-relaxed opacity-90">
              If your brand wants to be the name behind that incentive, or you just have thoughts on what a good reward looks like, email{' '}
              <a className="text-amber-300 underline" href="mailto:hello@globalcommunitycleaners.org?subject=Partner%20on%20points">
                hello@globalcommunitycleaners.org
              </a>{' '}
              with the subject line <strong>&ldquo;Partner on points&rdquo;</strong>. And users &mdash; reply to any of our channels and tell us what would make this worth doing for you.
            </p>
          </div>
        </Section>

        {/* §7 The ask */}
        <Section label="§7 · The ask">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            UIC funded 20 blocks worth of the goal. Here&rsquo;s how the next 80 get funded.
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
            <strong>$2,000</strong> = your name on our public wall, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition &mdash; the same recognition UIC is receiving in this issue.
          </p>
        </Section>

        {/* §8 What's next */}
        <Section label="§8 · What's next">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Next issue, we tell you how the first two days actually went.
          </h2>
          <p>
            The next report is the one we&rsquo;ve been building toward since Issue #1: names, photos, blocks &mdash; the residents who worked the first cleanups, what the app got right, and what we&rsquo;re fixing before the next community gets the $100 block mechanism.
          </p>
          <p className="mt-4 font-semibold">Dr. King&rsquo;s apartment is six blocks away. The work continues.</p>
          <p className="mt-2 italic text-neutral-500">— The team at Global Community Cleaners NFP</p>
        </Section>

        {/* Footer / share */}
        <div className="mt-16 border-t border-neutral-200 pt-8 text-center text-sm text-neutral-500">
          <p>
            <a className="text-emerald-700 underline" href="/sponsor">
              Sponsor a block →
            </a>
            {' · '}
            <a className="text-emerald-700 underline" href="/blog/2026-06-01-issue-02">
              Read Issue #2
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

function Issue4() {
  const media = '/newsletter/issue-02'
  return (
    <article className="bg-neutral-50 pb-24 pt-12">
      <div className="mx-auto max-w-3xl px-5">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-700">
            GCC Blog · Issue #4 · Growing Teeth
          </p>
          <p className="mt-2 text-sm text-neutral-500">July 31, 2026</p>
          <h1 className="mt-6 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            The scan system grows teeth
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-neutral-600">
            The first cleanups are on the books, a university&rsquo;s check came through the office built for this neighborhood, and the app learned to see what hasn&rsquo;t been scanned yet.
          </p>
        </header>

        {/* §1 Where we left off */}
        <Section label="§1 · Where we left off">
          <p>
            In{' '}
            <a className="font-semibold text-emerald-700 underline" href="/blog/2026-07-03-issue-03">
              Issue #3
            </a>
            , UIC had just backed the pilot, the first North Lawndale cleanups were days away, and the heatmap was about to start running on real scan data. All three happened. This issue is the field report: how the first two days went, what UIC&rsquo;s gift actually is, and how the scan system grew teeth over fourteen TestFlight builds.
          </p>
          <Pull>The plan from Issue #1 is now just the work. This is what the work looks like.</Pull>
        </Section>

        {/* §2 The first cleanups ran */}
        <Section label="§2 · The first cleanups ran">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            July 5 and 6, right after the holiday, our first crews worked North Lawndale blocks.
          </h2>
          <p>
            These were the alpha cleanups we described last issue &mdash; the first time real residents ran the full app-guided route process on real blocks, start to finish. We treated both days as a structured test, not a launch party: the residents doing the work told us where the app flow was smooth and where it clunked, and we wrote all of it down.
          </p>
          <p>
            That feedback is already shaping the builds you&rsquo;ll read about below. The <strong>$100-per-block</strong> route mechanism gets tightened against what we learned on those two days before it goes anywhere else.
          </p>
        </Section>

        {/* §3 The UIC gift, precisely */}
        <Section label="§3 · The UIC gift, precisely">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            UIC&rsquo;s $2,000 came through the office that works in exactly two neighborhoods &mdash; and we&rsquo;re one of them.
          </h2>
          <p>
            In Issue #3 we reported the University of Illinois Chicago&rsquo;s $2,000 donation toward the 100-block pilot. Here is the detail that makes it land: the gift arrived <strong>July 3</strong> through UIC&rsquo;s <strong>Office of Community Collaboration</strong> &mdash; the office that focuses its community work on exactly two Chicago neighborhoods: <strong>Auburn Gresham and North Lawndale</strong>.
          </p>
          <p>
            That matters. This isn&rsquo;t a university writing a check to a zip code it barely knows. UIC studies North Lawndale, works in North Lawndale, and now funds blocks in North Lawndale &mdash; through the office that exists for exactly that.
          </p>
        </Section>

        {/* §4 The scan overhaul */}
        <Section label="§4 · Fourteen builds later">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Between TestFlight builds 165 and 178, the iOS env-scan went through a full overhaul.
          </h2>
          <p>What shipped:</p>
          <div className="mt-4 space-y-3">
            <TypeCard n="01" name="Full-screen AR camera" desc="Scanning is the app now, not a feature buried in it. The camera fills the screen and the block fills the camera." />
            <TypeCard n="02" name="An outdoor gate" desc="The scanner knows when you're indoors and tells you to step outside — scans only count where the trash actually is." />
            <TypeCard n="03" name="Server-seeded fog in the AR view" desc="Areas nobody has scanned show up as fog right in the camera, so you can see the coverage gaps while you walk — and clear them." />
            <TypeCard n="04" name="Route laps with a guided first lap" desc="The app walks you through your first cleanup route step by step, then lets you run laps on your own." />
          </div>
          <p className="mt-6">
            Every one of these exists because of what the July 5&ndash;6 crews told us. The loop between the street and the build is short on purpose.
          </p>
        </Section>

        {/* §5 The heatmap sharpens */}
        <Section label="§5 · The heatmap sharpens">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            110+ hotspots mapped &mdash; and every scan session makes the picture more accurate.
          </h2>
          <p>
            Residents have now mapped <strong>110+ trash hotspots</strong> across North Lawndale &mdash; every one photographed, GPS-tagged, and timestamped. The scan-powered heatmap gets sharper with every session: each walk adds density data the last version of the map didn&rsquo;t have. The picture of the neighborhood we&rsquo;re building isn&rsquo;t a snapshot. It&rsquo;s a time series.
          </p>
          <div className="mt-5 rounded-xl border border-neutral-200 bg-white p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-neutral-500">Live heatmap · GCC mobile app</p>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={`${media}/heatmap.png`} alt="GCC app heatmap of North Lawndale showing 110+ reported hotspots" className="mx-auto mt-4 max-w-md rounded-lg" />
            <p className="mt-3 text-center text-xs italic text-neutral-500">
              The map is open and growing. See it live on the{' '}
              <a className="text-emerald-700 underline" href="/bounties">bounties map</a>.
            </p>
          </div>
        </Section>

        {/* §6 The ask */}
        <Section label="§6 · The ask">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            UIC put 20 blocks&rsquo; worth of funding toward the goal. Here&rsquo;s how the rest get funded.
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
            <strong>$2,000</strong> = your name on our public wall, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition &mdash; the same recognition UIC is receiving.
          </p>
        </Section>

        {/* §7 What's next */}
        <Section label="§7 · What's next">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Next issue: the payoff numbers &mdash; and what the scan data becomes when you stack it up.
          </h2>
          <p>
            Blocks cleaned, dollars paid out, and a first look at where all this scanning is headed. A hint: it&rsquo;s three-dimensional.
          </p>
          <p className="mt-4 font-semibold">Dr. King&rsquo;s apartment is six blocks away. The work continues.</p>
          <p className="mt-2 italic text-neutral-500">— The team at Global Community Cleaners NFP</p>
        </Section>

        {/* Footer / share */}
        <div className="mt-16 border-t border-neutral-200 pt-8 text-center text-sm text-neutral-500">
          <p>
            <a className="text-emerald-700 underline" href="/sponsor">
              Sponsor a block →
            </a>
            {' · '}
            <a className="text-emerald-700 underline" href="/blog/2026-07-03-issue-03">
              Read Issue #3
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

function Issue5() {
  const media = '/newsletter/issue-02'
  return (
    <article className="bg-neutral-50 pb-24 pt-12">
      <div className="mx-auto max-w-3xl px-5">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-700">
            GCC Blog · Issue #5 · The Digital Twin
          </p>
          <p className="mt-2 text-sm text-neutral-500">August 19, 2026</p>
          <h1 className="mt-6 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            From cleaning blocks to mapping them in 3D
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-neutral-600">
            Twenty blocks cleaned, $2,000 paid to the residents who cleaned them, and a scan archive that&rsquo;s becoming a three-dimensional record of the neighborhood &mdash; built for accountability, not for show.
          </p>
        </header>

        {/* §1 Where we left off */}
        <Section label="§1 · Where we left off">
          <p>
            In{' '}
            <a className="font-semibold text-emerald-700 underline" href="/blog/2026-07-31-issue-04">
              Issue #4
            </a>
            , the first cleanups had just run and the scan system had grown teeth &mdash; full-screen AR camera, coverage fog, guided routes. A month on, we crossed the pilot&rsquo;s first real milestone, and the scan archive started turning into something we didn&rsquo;t have a word for yet. Now we do: a digital twin.
          </p>
          <Pull>Before-and-after photos were the proof of concept. Before-and-after 3D is the proof.</Pull>
        </Section>

        {/* §2 The milestone */}
        <Section label="§2 · The milestone">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            20 of 100 blocks cleaned. $2,000 paid out to North Lawndale residents.
          </h2>
          <p>
            One hundred dollars per block, paid directly to the person who did the work on their own street. That&rsquo;s 20% of the pilot goal &mdash; done, documented, and paid &mdash; with 80 blocks and 80 paid residents to go.
          </p>
          <div className="mt-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-emerald-800">Goal · North Lawndale pilot</p>
            <p className="mt-1 text-lg font-bold text-neutral-900">100 blocks cleaned · 100 community members paid</p>
            <div className="mt-3 flex h-5 overflow-hidden rounded-full border border-emerald-200 bg-white">
              <div className="flex items-center justify-center bg-emerald-700 text-xs font-bold text-white" style={{ width: '20%' }}>
                20%
              </div>
            </div>
            <p className="mt-2 text-xs text-neutral-500">20 blocks cleaned and paid out so far. Sponsor a block to put number 21 on a resident&rsquo;s street.</p>
          </div>
        </Section>

        {/* §3 What a scan captures now */}
        <Section label="§3 · What a scan captures now">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            A scan is no longer a photo with a pin. It&rsquo;s a measured walk through real space.
          </h2>
          <p>
            Every scan session now captures three streams at once: <strong>LiDAR depth</strong>, <strong>camera poses</strong>, and <strong>1Hz GPS</strong> &mdash; uploading in chunks to cloud storage as the user walks.
          </p>
          <p>
            <strong>158 scan sessions</strong> captured so far. Each one is a stretch of North Lawndale measured twice &mdash; once by the camera, once by the satellite.
          </p>
        </Section>

        {/* §4 The digital twin */}
        <Section label="§4 · The digital twin">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            The reconstruction pipeline fuses all that depth into per-block 3D &mdash; and it matches the street to the meter.
          </h2>
          <p>
            The ARKit camera poses anchoring each model have been verified metric-accurate against GPS. What comes out the other end is a block-by-block three-dimensional model of the neighborhood, built from residents&rsquo; walks.
          </p>
          <p>
            Let&rsquo;s be clear about what this is for. It&rsquo;s not a game and it&rsquo;s not a toy rendering. It&rsquo;s <strong>accountability infrastructure</strong>. When a block gets cleaned, nobody has to take our word for it &mdash; we can show the block before and after, in three dimensions, measured the same way both times. A sponsor&rsquo;s $100 buys a cleanup. The twin proves it.
          </p>
        </Section>

        {/* §5 Teaching the app to see litter */}
        <Section label="§5 · Teaching the app to see litter">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            A litter-classifier model is in training &mdash; partly on our own blocks.
          </h2>
          <p>
            The model trains on <strong>TACO</strong>, the open trash-annotation dataset, plus <strong>field-labelled crops from our own scans</strong>. North Lawndale&rsquo;s litter is specific, and a model trained partly on our own blocks will recognize it better than one trained on the internet&rsquo;s. When it&rsquo;s ready, identification moves <strong>on-device</strong>: the app names what it sees while you scan, no upload required.
          </p>
        </Section>

        {/* §6 Shipping + honor wall */}
        <Section label="§6 · Shipping, and naming the people who fund it">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            iOS v1.0.8 is in TestFlight &mdash; and the honor wall is up.
          </h2>
          <p>
            Version 1.0.8 (build 181) carries the capture and upload work above. On the site, a new <strong>honor wall</strong> now recognizes our major sponsors &mdash; the organizations that have given <strong>$2,000 or more</strong> &mdash; starting with UIC. The people and institutions funding this work should be named where everyone can see them.
          </p>
        </Section>

        {/* §7 The ask */}
        <Section label="§7 · The ask">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            20 down, 80 to go. Here&rsquo;s how the next block gets funded.
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
            <strong>$2,000</strong> = your name on the honor wall alongside UIC, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition.
          </p>
        </Section>

        {/* §8 What's next */}
        <Section label="§8 · What's next">
          <h2 className="text-3xl font-bold leading-tight text-neutral-900">
            Next issue: more blocks, more sessions, and the first before-and-after you can orbit.
          </h2>
          <p>
            The twin is about to start doing the talking &mdash; a cleaned block you can look at from every angle, before and after, measured both times.
          </p>
          <p className="mt-4 font-semibold">Dr. King&rsquo;s apartment is six blocks away. The work continues.</p>
          <p className="mt-2 italic text-neutral-500">— The team at Global Community Cleaners NFP</p>
        </Section>

        {/* Footer / share */}
        <div className="mt-16 border-t border-neutral-200 pt-8 text-center text-sm text-neutral-500">
          <p>
            <a className="text-emerald-700 underline" href="/sponsor">
              Sponsor a block →
            </a>
            {' · '}
            <a className="text-emerald-700 underline" href="/blog/2026-07-31-issue-04">
              Read Issue #4
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

function TypeCard({ n, name, desc }: { n: string; name: string; desc: string }) {
  return (
    <div className="flex gap-4 rounded-lg border border-neutral-200 bg-white px-5 py-4">
      <span className="font-serif text-2xl font-bold text-emerald-700">{n}</span>
      <div>
        <p className="font-bold text-neutral-900">{name}</p>
        <p className="mt-1 text-sm leading-relaxed text-neutral-600">{desc}</p>
      </div>
    </div>
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
