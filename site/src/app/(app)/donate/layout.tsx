import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Donate',
  description:
    'Sponsor a block in North Lawndale for $100. 100% of your donation goes directly to the worker who cleans it. Choose a block, fund it, and watch the transformation.',
  openGraph: {
    title: 'Donate | Global Community Cleaners',
    description:
      'Sponsor a block for $100. Every cent goes to a local worker. Pick a block on the map and fund real change in North Lawndale, Chicago.',
  },
}

export default function DonateLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return children
}
