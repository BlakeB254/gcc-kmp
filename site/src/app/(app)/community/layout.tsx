import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Community Forum',
  description:
    'Connect with fellow GCC workers and sponsors. Share cleaning tips, local news, feature requests, and stories from the neighborhood.',
  openGraph: {
    title: 'Community Forum | Global Community Cleaners',
    description:
      'The GCC community forum. Discuss tips, share stories, request features, and stay connected with workers and sponsors across North Lawndale.',
  },
}

export default function CommunityLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return children
}
