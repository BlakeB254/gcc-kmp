import type { Metadata } from 'next'

const siteUrl = 'https://north-lawndale.globalcommunitycleaners.org'

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: 'GCC North Lawndale | Clean Blocks. Paid Workers. Community Pride.',
  description:
    'Global Community Cleaners is at work in North Lawndale, Chicago. Join your neighbors to clean blocks, earn $100 same-day, and build the community Dr. King fought for.',
  keywords: [
    'North Lawndale',
    'Chicago',
    'community cleaning',
    'block cleaning',
    'Global Community Cleaners',
    'GCC',
    'North Lawndale jobs',
    'paid cleaning',
    'community pride',
    'MLK Chicago',
  ],
  openGraph: {
    title: 'GCC North Lawndale | Clean Blocks. Paid Workers. Community Pride.',
    description:
      'Dr. King chose North Lawndale. So did we. Join your neighbors — earn $100/block, same-day pay, no phone needed.',
    url: siteUrl,
    siteName: 'GCC North Lawndale',
    locale: 'en_US',
    type: 'website',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'GCC North Lawndale | Earn $100/Block',
    description:
      'Clean blocks. Get paid same day. No phone needed. Join the movement in North Lawndale, Chicago.',
  },
  alternates: {
    canonical: siteUrl,
  },
}

export default function NorthLawndaleLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return <>{children}</>
}
