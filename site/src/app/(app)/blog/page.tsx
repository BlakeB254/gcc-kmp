import Link from 'next/link'

// Dynamic so the scheduled-publish filter is evaluated live on every request —
// Issue #2's card appears exactly at its publishAt moment.
export const dynamic = 'force-dynamic'

interface PostCard {
  slug: string
  title: string
  date: string
  description: string
  heroImage: string
  badge: string
  // ISO timestamp (with offset) before which the card is hidden in production.
  publishAt: string
}

// Hidden until publishAt passes. Gated purely on the wall clock — no NODE_ENV
// dependency (it leaked into the live runtime). Mirrors blog/[slug]/page.tsx.
function isPublished(publishAt: string): boolean {
  return Date.now() >= new Date(publishAt).getTime()
}

const POSTS: PostCard[] = [
  {
    slug: '2026-07-03-issue-03',
    title: 'Boots on the ground',
    date: '2026-07-03',
    publishAt: '2026-07-03T10:00:00-05:00',
    description:
      "Issue #3 — Boots on the Ground. A cleaner site, a live iOS scanning feature (Android coming soon), a $2,000 gift from the University of Illinois Chicago toward our 100-block goal, the first North Lawndale cleanups landing July 5-6, a scan-powered heatmap getting sharper, and the next layer of our points economy taking shape.",
    heroImage: '/newsletter/issue-02/heatmap.png',
    badge: 'Issue #3 · Boots on the Ground',
  },
  {
    slug: '2026-06-01-issue-02',
    title: 'Reading the map',
    date: '2026-06-01',
    publishAt: '2026-06-01T08:00:00-05:00',
    description:
      "Issue #2 — The Hotspot Report. A month deeper into the field evidence: what 110+ resident-mapped hotspots actually tell us, the four kinds of blight we keep finding, the corridors we can already name — and a direct invitation to the Chicago businesses that want to back our points economy.",
    heroImage: '/newsletter/issue-02/heatmap.png',
    badge: 'Issue #2 · The Hotspot Report',
  },
  {
    slug: '2026-05-01-issue-01',
    title: 'Sixty years and two blocks',
    date: '2026-05-01',
    publishAt: '2026-05-01T00:00:00-05:00',
    description:
      "Issue #1 — The Founder's Letter. Why GCC exists, the 110+ hotspots residents have already mapped in our app, the patterns we're seeing, the academic data behind the model, and how everyone — local or worldwide — can participate.",
    heroImage: '/newsletter/issue-01/heatmap.png',
    badge: 'Issue #1 · Founder\'s Letter',
  },
]

export const metadata = {
  title: 'Blog — Global Community Cleaners',
  description:
    "GCC's official newsletter and field-evidence reports. Block-by-block updates, founder writing, and civic-cleanliness data from North Lawndale and beyond.",
}

export default function BlogIndexPage() {
  const sorted = [...POSTS]
    .filter((post) => isPublished(post.publishAt))
    .sort((a, b) => b.date.localeCompare(a.date))

  return (
    <div className="min-h-screen bg-neutral-50 pb-24 pt-16">
      <div className="mx-auto max-w-4xl px-5">
        <header className="text-center">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-700">
            GCC · The Newsletter & Field Reports
          </p>
          <h1 className="mt-4 font-serif text-5xl font-bold tracking-tight text-neutral-900 sm:text-6xl">
            The Blog
          </h1>
          <p className="mx-auto mt-5 max-w-2xl text-base leading-relaxed text-neutral-600">
            Founder writing, block-by-block before-and-after reports, and the
            field-evidence data behind every sponsored cleanup. New issues drop
            on the first of each month.
          </p>
        </header>

        <div className="mt-16 grid gap-8 sm:grid-cols-2">
          {sorted.map((post) => (
            <Link
              key={post.slug}
              href={`/blog/${post.slug}`}
              className="group overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-neutral-200 transition hover:shadow-lg hover:ring-emerald-200"
            >
              <div className="relative aspect-[16/10] overflow-hidden bg-emerald-900">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={post.heroImage}
                  alt={post.title}
                  className="h-full w-full object-cover transition group-hover:scale-[1.02]"
                />
              </div>
              <div className="p-6">
                <p className="text-xs font-bold uppercase tracking-widest text-emerald-700">
                  {post.badge}
                </p>
                <h2 className="mt-3 font-serif text-2xl font-bold leading-tight text-neutral-900 group-hover:text-emerald-800">
                  {post.title}
                </h2>
                <p className="mt-3 line-clamp-3 text-sm leading-relaxed text-neutral-600">
                  {post.description}
                </p>
                <div className="mt-5 flex items-center justify-between text-xs text-neutral-500">
                  <span>
                    {new Date(post.date).toLocaleDateString('en-US', {
                      year: 'numeric',
                      month: 'long',
                      day: 'numeric',
                    })}
                  </span>
                  <span className="font-semibold text-emerald-700 group-hover:underline">
                    Read →
                  </span>
                </div>
              </div>
            </Link>
          ))}
        </div>

        <div className="mt-20 rounded-2xl border border-emerald-200 bg-emerald-50 px-8 py-10 text-center">
          <p className="text-xs font-bold uppercase tracking-widest text-emerald-800">
            Don't miss the next one
          </p>
          <h2 className="mt-3 font-serif text-2xl font-bold text-neutral-900">
            Subscribe to GCC updates
          </h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-neutral-700">
            Block-by-block before-and-after reports land in your inbox on the
            first of every month. Sponsors and waitlist members are
            auto-subscribed.
          </p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
            <Link
              href="/sponsor"
              className="rounded-lg bg-emerald-700 px-5 py-3 text-sm font-semibold text-white hover:bg-emerald-800"
            >
              Sponsor a block — $100
            </Link>
            <Link
              href="/waitlist"
              className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-emerald-800 ring-1 ring-emerald-300 hover:bg-emerald-100"
            >
              Join the waitlist
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
