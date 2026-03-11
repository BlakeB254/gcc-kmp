import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Join the Waitlist',
  description:
    'Sign up to join Global Community Cleaners. Whether you want to earn money cleaning blocks or sponsor neighborhood transformation, add your name to the waitlist.',
  openGraph: {
    title: 'Join the Waitlist | Global Community Cleaners',
    description:
      'Join the movement to transform North Lawndale. Sign up as a future worker, sponsor, or supporter and be first in line when GCC launches in your area.',
  },
}

export default function JoinLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return children
}
