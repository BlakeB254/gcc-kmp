import type { MetadataRoute } from 'next'

const siteUrl = 'https://globalcommunitycleaners.org'

type Entry = [path: string, changeFrequency: 'daily' | 'weekly' | 'monthly' | 'yearly', priority: number]

// Every public page that should be found by search. Each path must have a
// page under src/app — a path with no page is a 404 in the sitemap.
const PAGES: Entry[] = [
  ['', 'weekly', 1],
  ['/sponsor', 'weekly', 0.9],
  ['/business', 'weekly', 0.9],
  ['/donate', 'weekly', 0.9],
  ['/worker/signup', 'weekly', 0.9],
  ['/how-it-works', 'monthly', 0.8],
  ['/about', 'monthly', 0.8],
  ['/blog', 'weekly', 0.8],
  ['/impact-supporters', 'weekly', 0.7],
  ['/sponsor/calculator', 'monthly', 0.7],
  ['/sponsor/enterprise', 'monthly', 0.6],
  ['/sponsor/wall', 'weekly', 0.6],
  ['/residents', 'monthly', 0.6],
  ['/bounties', 'weekly', 0.6],
  ['/bounties/how-it-works', 'monthly', 0.5],
  ['/android-beta', 'weekly', 0.6],
  ['/app', 'monthly', 0.6],
  ['/join', 'monthly', 0.5],
  ['/nominate', 'monthly', 0.5],
  ['/in-kind', 'monthly', 0.5],
  ['/faq', 'monthly', 0.5],
  ['/north-lawndale', 'monthly', 0.5],
  ['/app/indoor-gate', 'monthly', 0.4],
  ['/support', 'monthly', 0.4],
  ['/privacy', 'yearly', 0.3],
  ['/terms', 'yearly', 0.3],
  ['/delete-account', 'yearly', 0.2],
]

// Published blog issues: slug and publish time. Add a line when an issue is
// added to SCHEDULE in app/(app)/blog/[slug]/page.tsx.
const BLOG_POSTS: Array<[slug: string, publishAt: string]> = [
  ['2026-05-01-issue-01', '2026-05-01T00:00:00-05:00'],
  ['2026-06-01-issue-02', '2026-06-01T08:00:00-05:00'],
  ['2026-07-03-issue-03', '2026-07-01T08:00:00-05:00'],
  ['2026-08-01-issue-05', '2026-08-01T08:00:00-05:00'],
  ['2026-09-01-issue-06', '2026-09-01T08:00:00-05:00'],
  ['2026-10-01-issue-07', '2026-10-01T08:00:00-05:00'],
]

export default function sitemap(): MetadataRoute.Sitemap {
  const lastModified = new Date()

  return [
    ...PAGES.map(([path, changeFrequency, priority]) => ({
      url: `${siteUrl}${path}`,
      lastModified,
      changeFrequency,
      priority,
    })),
    ...BLOG_POSTS.filter(([, publishAt]) => Date.now() >= new Date(publishAt).getTime()).map(
      ([slug, publishAt]) => ({
        url: `${siteUrl}/blog/${slug}`,
        lastModified: new Date(publishAt),
        changeFrequency: 'yearly' as const,
        priority: 0.6,
      }),
    ),
  ]
}
