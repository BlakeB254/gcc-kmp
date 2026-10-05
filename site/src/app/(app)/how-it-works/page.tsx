import { Metadata } from 'next'
import { HowItWorksTabs } from './HowItWorksTabs'

export const metadata: Metadata = {
  title: 'How It Works',
  description:
    'How GCC works for sponsors and workers — the $100/block model, earning as a worker, funding as a sponsor, and what the app does.',
}

export default function HowItWorksPage() {
  return <HowItWorksTabs />
}
