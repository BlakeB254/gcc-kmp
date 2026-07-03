/**
 * Article store. Currently a hardcoded list — these are the same posts
 * rendered at /blog/[slug] on the marketing site, mirrored here so the
 * iOS app can fetch them as JSON without scraping HTML.
 *
 * When the marketing site moves blog content into a real CMS or MDX
 * collection, replace the body of `getArticles()` / `getArticle()` with
 * the corresponding fetch and keep the JSON shape identical.
 */

export interface Article {
  slug: string
  title: string
  excerpt: string
  author: string | null
  publishedAt: string // ISO 8601
  coverImageUrl: string | null
  tags: string[]
  bodyMarkdown: string
  webUrl: string | null
}

export type ArticleSummary = Omit<Article, 'bodyMarkdown' | 'webUrl'>

const ARTICLES: Article[] = [
  {
    slug: '2026-07-03-issue-03',
    title: 'Boots on the ground',
    excerpt:
      "Issue #3 — a cleaner site, a live iOS scanning feature (Android coming soon), a $2,000 gift from the University of Illinois Chicago toward our 100-block goal, the first North Lawndale cleanups landing July 5-6, a scan-powered heatmap getting sharper, and the next layer of our points economy.",
    author: 'The team at Global Community Cleaners NFP',
    publishedAt: '2026-07-03T15:00:00Z',
    coverImageUrl: null,
    tags: ['Newsletter', 'Issue 3', 'North Lawndale', 'UIC', 'Gamification'],
    webUrl: 'https://globalcommunitycleaners.org/blog/2026-07-03-issue-03',
    bodyMarkdown: `# Boots on the ground

_Global Community Cleaners — Issue #3 · July 3, 2026_

A cleaner front door, a gift from a university that studies this exact problem, and a date on the calendar: our first crews hit North Lawndale streets this week.

## §1 · Where we left off

In [Issue #2](https://globalcommunitycleaners.org/blog/2026-06-01-issue-02), the map had 110+ hotspots, the equipment was in hand thanks to Fields of Dreams Chicago, and we were closing the funding to put the first crews on the street. Three things changed since: the site got a real front door, the app grew a new set of eyes, and a university put real money behind the goal.

> Equipment was never the hard part. Funded blocks and a first date were. We have both now.

## §2 · A cleaner front door

### The site got easier to read. The app got a new way to see the block.

We rebuilt the homepage and navigation so the site says what we do in the first five seconds instead of the first scroll. Same organization, same mission — just less digging to find the part you came for.

On the app side: the GCC scanning feature is live on iOS today, letting you walk a block and capture its condition as you go rather than logging one spot at a time. **Android is coming soon** to Google Play — we know a large share of North Lawndale carries Android, and closing that gap is one of our top priorities this summer.

## §3 · A university backs the goal

### The University of Illinois Chicago gave $2,000 toward our 100-block pilot.

This is the headline of this issue. **UIC has donated $2,000** toward Global Community Cleaners' pilot goal of cleaning 100 blocks and creating paid work for local residents in North Lawndale. UIC's own Great Cities Institute is one of the sources we cite when we talk about the conditions here — a university that studies this neighborhood's data is now funding blocks in it.

To be precise about what this gift is and isn't: it's money allocated toward the 100-block goal, today, before the first shovel touches ground on the blocks it funds. We'll report back with the before-and-after photos and the names of the residents it puts to work, the same way we do for every sponsor — once that work is actually done.

- Goal: 100 blocks cleaned, 100 community members paid.
- 20 blocks funded toward that goal so far, UIC's gift among them.

## §4 · The first cleanups land this week

### July 5 and 6, right after the holiday, our first crews work North Lawndale blocks.

These are our **alpha cleanups** — the first time real residents run the full app-guided route-fulfillment process on real blocks, start to finish. We're treating both days as a structured test, not just a launch: watching where the app flow is smooth, where it's clunky, and listening hard to what the residents doing the work tell us about their experience.

That feedback is the point. We are not rolling the **$100-per-block** route mechanism out to more communities until we've tightened it against what we learn from these two days.

> If you worked one of the first two blocks and have thoughts on what to fix, we want to hear all of it — the app, the payout, the instructions, anything.

## §5 · The map gets sharper

### The heatmap now runs on real scan data, and it's about to look a lot more accurate.

Every scan a user logs — whether it's a full block walk or a single spot report — feeds a live cleanliness heatmap of North Lawndale. Zoomed out, you see the neighborhood-level picture: where conditions cluster, where they don't.

Zoomed in is where we're upgrading. We're migrating close-zoom rendering away from generic dots toward a visual that actually represents what a block looks like — density and severity of reported conditions, not just a pin count. This is the part of GCC that isn't a cleaning service at all: it's the data infrastructure for knowing what "clean" and "not clean" actually look like, block by block, backed by photos a neighbor took.

## §6 · Building an economy worth participating in

### The next reward layer is about the areas nobody has looked at yet.

We're adding scans of **never-before-scanned areas**, and areas that haven't been scanned in the **last 24 hours**, into the points system — rewarding the "observe and report" side of the app the same way we already reward cleanup work. We're still working out the exact numbers, and we're still looking for the right sponsors to back this specific mechanic. And if you're a user: tell us what rewards would actually make you want to go scan a block nobody's looked at yet. We're building this for you, so we want your answer before we lock it in.

A few other pieces of the economy, already live or in progress:

- **Self-initiated cleanups earn points.** Spot something, clean it yourself, log it — that's the core loop, and we want to keep incentivizing it.
- **Anyone can post a paid bounty** on a mess they want gone, and a worker can claim it for cash.
- **Bounty pay and points aren't double-dipped the same way.** If you complete a paid bounty, you still earn some points for the work — just less than you'd earn cleaning the same mess for free. Free cleanups are the highest-points path on purpose.
- **A full-lifecycle bonus is in the works** — extra points for observing a mess, recording it, cleaning it, and verifying the clean result, all in one session. The idea is that closing the whole loop yourself should pay out more than the sum of the individual steps, not just add them up. We're still finalizing the numbers and will publish them once they're set.

### Sponsors, this ask is specific

We're looking for a sponsor to back the scan-and-discover side of the points economy — rewarding residents for mapping blocks nobody's looked at yet. If your brand wants to be the name behind that incentive, or you just have thoughts on what a good reward looks like, email [hello@globalcommunitycleaners.org](mailto:hello@globalcommunitycleaners.org?subject=Partner%20on%20points) with the subject line **"Partner on points"**. And users — reply to any of our channels and tell us what would make this worth doing for you.

## §7 · The ask

### UIC funded 20 blocks worth of the goal. Here's how the next 80 get funded.

[Sponsor a block — $100](https://globalcommunitycleaners.org/sponsor) · [Recognized sponsor — $2,000](https://globalcommunitycleaners.org/sponsor?amount=2000)

**$100** = one cleanup, one paid resident, one before-and-after photo set in your inbox.

**$2,000** = your name on our public wall, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition — the same recognition UIC is receiving in this issue.

## §8 · What's next

### Next issue, we tell you how the first two days actually went.

The next report is the one we've been building toward since Issue #1: names, photos, blocks — the residents who worked the first cleanups, what the app got right, and what we're fixing before the next community gets the $100 block mechanism.

Dr. King's apartment is six blocks away. The work continues.

— The team at Global Community Cleaners NFP

[Sponsor a block →](https://globalcommunitycleaners.org/sponsor)
`,
  },
  {
    slug: '2026-05-01-issue-01',
    title: 'Sixty years and two blocks',
    excerpt:
      "Issue #1 — where we come from, what we already know about our neighborhood, and what we're going to do about it. 110+ hotspots already mapped, two patterns the data revealed, and the model behind paying residents $100 to clean their own block.",
    author: 'The team at Global Community Cleaners NFP',
    publishedAt: '2026-05-01T12:00:00Z',
    coverImageUrl: null,
    tags: ['Newsletter', 'Issue 1', 'North Lawndale', 'Community'],
    webUrl: 'https://globalcommunitycleaners.org/blog/2026-05-01-issue-01',
    bodyMarkdown: `# Sixty years and two blocks

_Global Community Cleaners — Issue #1 · May 1, 2026_

Three things: where we come from, what we already know about our neighborhood, and what we're going to do about it.

## §1 · The history

On **January 26, 1966**, Dr. Martin Luther King Jr. carried his bags up to a third-floor walk-up at **1550 South Hamlin Avenue** in North Lawndale and started what he called the "action phase" of the Chicago Freedom Movement — the campaign that brought him here to focus on housing conditions and economic opportunity in Chicago neighborhoods. The campaign produced the Summit Agreement, which fed directly into the 1968 Fair Housing Act.

Today, Global Community Cleaners is headquartered at **1844 South Harding, 2nd Floor**, a six-block walk from the apartment Dr. King moved into.

> Sixty years. Two blocks of distance. Same neighborhood, same work.

## §2 · The data we already hold

### We're not a cleaning service. We're the field-evidence layer for community cleanliness in North Lawndale.

Most organizations talk about a problem. We hold the data on it. The GCC mobile app is live on iOS (Android version in development), and North Lawndale residents have already used it to map — with photos, GPS, and timestamps — **110+ trash and dumping hotspots** across the community.

Every red cluster on the live heatmap is a real, photographed hotspot a resident reported. The denser the heat, the more accumulation that block has seen.

## §3 · What 110 hotspots actually look like

### We documented it because reading it doesn't do it justice.

A small sample from the field. Three of the 110+ photographs residents have logged in the app — all in or immediately around North Lawndale, all captured in the last several weeks.

- Dumped cardboard, residential block.
- Construction debris on vacant land.
- Wind-driven accumulation along fenceline.

## §4 · Patterns the data reveals

### 110 photographs add up to a small body of urban evidence. Two patterns jumped out.

**Pattern 1 · Wind-driven fenceline accumulation**

Fencelines bordering vacant fields and parking lots are catching disproportionate trash — not because residents dump there, but because **wind funnels light debris into them** and nothing breaks the pattern.

**Long-term fix:** simple windbreak plantings or screen fabric along the worst fencelines, plus a cleanup cadence keyed to wind events. Recoverable infrastructure investment, not a cleanup forever-loop.

**Pattern 2 · Walkway corridors without trash receptacles**

Several major pedestrian corridors in North Lawndale have **no public trash cans for blocks at a time**. Hotspot density along these corridors is measurably higher than in zones with built-in receptacles.

**Long-term fix:** targeted bin placement at high-foot-traffic intersections — either a city ask, a property-owner partnership, or a sponsored bin program. We can name the corridors. We have the data.

This is the real differentiator. We clean the blocks, and we identify what infrastructure could stop the next round of accumulation before it starts.

## §5 · Why this works

### A randomized citywide trial — the gold standard — proved this model works.

Charles Branas's team at Penn ran a cluster-randomized trial across Philadelphia, comparing **greened lots**, trash-removal-only lots, and untouched controls over 18 months. The results, published in _PNAS_ and _JAMA Network Open_, are some of the strongest evidence in the urban-policy literature for any intervention at any cost.

- Branas trial results: 29.1% drop in gun assaults, 41.5% fewer depression reports, 68.7% drop for low-income residents.
- $7.43 in additional municipal tax revenue per public dollar spent on greening.

The newer research goes further. A 2019 meta-analysis in _Social Science & Medicine_ found that the original broken-windows hypothesis works mostly as a downstream signal of **collective efficacy** — whether neighbors feel any agency over their block. Robert Sampson's Chicago research showed that neighborhoods scoring high on collective efficacy run roughly **40% lower** crime rates, even when income, race, and visible disorder look similar on paper.

The lesson, repeated across every modern study: invest in the people on the block, and the block follows.

## §6 · The neighborhood we're working in

### The municipal data on North Lawndale isn't subtle.

- 20.7-year life-expectancy gap: 87.3 in The Loop vs 66.6 in West Garfield Park.

Black Chicagoans died **10.6 years earlier** than non-Black Chicagoans in 2023 (CDPH). North Lawndale's per-capita violent-crime rate runs roughly **3× the citywide rate**; it sits on the city's official list of areas "most affected by violence" (UIC Great Cities Institute, 2022). This is the gap a $100 block fee, paid to the resident on the block, is built to start closing.

## §7 · Meet the founder

### Shaquille Tate built it because he was tired of seeing it.

Born at Mt. Sinai Hospital in North Lawndale. Raised on the blocks running from **15th Street to Rideway**. Drives Divvy across the entire city for his day job — which means he sees what every Chicago neighborhood looks like from the curb. Asked on a 2026 radio interview how the idea found him, he answered:

> I had to come back to the North Lawndale community, and I'm walking past abandoned houses — boards on the windows, broken windows, trash everywhere on the floor. What if somebody could get all these kids to clean up their block? And if they were to clean up their block, to get paid to clean up their block. I'm employing elders and I'm employing youth and I'm paying them a hundred dollars to clean up their blocks.

He's right. It is about cleaning, but it's also about cash, dignity, ownership, and the simple fact that **the people best equipped to take care of a block are the people who live on it** — if someone is willing to pay them like the work matters. Because it does.

## §8 · How the model works

### Plain on purpose.

- $100 cleans one block. 100% of it goes to the resident doing the work.

One participant per block. Elders _and_ youth — the model is intergenerational by design. Before-and-after photos to every sponsor; block status logged in our system. The same measurement discipline that turned greening from a hunch into peer-reviewed practice.

A **$100 block sponsorship** gets your name on our public sponsor wall. A **$2,000+ sponsorship** unlocks the recognition tier — name on the wall, digital certificate of appreciation, 501(c)(3) tax receipt, monthly newsletter feature, and social-media recognition across our channels.

## §9 · Anyone, anywhere can participate

### You don't have to live in Chicago to put a hotspot on the map.

The GCC app is global. Any user, anywhere in the world, can **spot** illegal dumping, blight, or unsafe conditions in their own neighborhood, photograph it, and add it to the map. Other users earn points by **verifying** a spot is real, still there, or has been resolved. The data layer compounds with every contribution.

We're building this into a **points system worth participating in**. We are actively working with local businesses to back the points with rewards our community actually wants:

**Reward types in development**

- Sports & concert tickets
- Gift cards from neighborhood businesses
- Restaurant credits
- Discounts from partner retailers
- Redeemable points across our internal network

### Local businesses, this is your invitation

If you can put real value behind these points — tickets, gift cards, table reservations, in-store credits — we will put your brand directly in front of the residents already cleaning their own streets.

It's the cleanest community-affairs ROI in Chicago: documented impact, photographable proof, audience that lives right where you sell. Email [hello@globalcommunitycleaners.org](mailto:hello@globalcommunitycleaners.org?subject=Partner%20on%20points) with the subject line **"Partner on points"**.

## §10 · The ask

### If this resonates, here is exactly what to do with that.

[Sponsor a block — $100](https://globalcommunitycleaners.org/sponsor) · [Recognized sponsor — $2,000](https://globalcommunitycleaners.org/sponsor?amount=2000)

**$100** = one cleanup, one paid resident, one before-and-after photo set in your inbox.

**$2,000** = your name on our public wall, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition.

## §11 · What's next

### Right now we're lining up the first 20 cleanups.

Twenty blocks — twenty paid residents — **20% of our way to the 100-block goal** we set for the North Lawndale pilot. One hundred blocks cleaned, one hundred community members earning real money on their own street. That's the floor, not the ceiling.

The first 20 are pending a soft commitment we're closing with a local partner; we'll name them in the next issue once the funding has cleared and the work is underway. In the meantime, every $100 block sponsorship we receive between now and then funds an additional cleanup on top of those 20.

**In June** we publish the first block-by-block before-and-after report. **In July** we feature the residents who claimed those first cleanups: names, photos, blocks.

In the meantime — Dr. King's apartment is six blocks away. The work continues.

— The team at Global Community Cleaners NFP

[Sponsor a block →](https://globalcommunitycleaners.org/sponsor)
`,
  },
]

export function getArticles(): ArticleSummary[] {
  return ARTICLES.map(({ bodyMarkdown, webUrl, ...summary }) => summary).sort(
    (a, b) => b.publishedAt.localeCompare(a.publishedAt),
  )
}

export function getArticle(slug: string): Article | null {
  return ARTICLES.find((a) => a.slug === slug) ?? null
}
