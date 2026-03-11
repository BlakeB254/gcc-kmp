import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Community Bounties',
  description:
    'See something dirty? Post a bounty and a community member will clean it up. Browse active bounties, spot reports, service blocks, and routes across North Lawndale.',
  openGraph: {
    title: 'Community Bounties | Global Community Cleaners',
    description:
      'Peer-to-peer cleanup bounties for North Lawndale. Post a bounty on a dirty spot and someone in the community will claim it and clean it up.',
  },
}

export default function BountiesLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return children
}
