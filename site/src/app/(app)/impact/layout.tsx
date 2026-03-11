import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Our Impact',
  description:
    'Track the real-time impact of Global Community Cleaners. See blocks cleaned, workers paid, sponsors contributing, and the movement growing across North Lawndale.',
  openGraph: {
    title: 'Our Impact | Global Community Cleaners',
    description:
      'Live progress on blocks cleaned, workers empowered, and neighborhoods transformed. See the science and stories behind GCC\'s community impact.',
  },
}

export default function ImpactLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return children
}
