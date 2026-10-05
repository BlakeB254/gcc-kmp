import type { Metadata } from 'next'

// /sponsor and its sub-pages are client components, which cannot export
// metadata; without this they all showed the site's default title. No
// canonical here: a layout's canonical is inherited by every page under it,
// which would mark /sponsor/wall and /sponsor/enterprise as copies of /sponsor.
export const metadata: Metadata = {
  title: 'Sponsor a Block',
  description:
    'Sponsor a block cleaning for $100. A local resident cleans it and is paid for the work; you get before-and-after photos and a tax receipt.',
  openGraph: {
    title: 'Sponsor a Block — $100 | Global Community Cleaners',
    description:
      'A local resident cleans the block and is paid for the work. You get before-and-after photos and a tax receipt.',
  },
}

export default function SponsorLayout({ children }: { children: React.ReactNode }) {
  return children
}
