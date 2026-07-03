'use client'

import { Suspense, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import Link from 'next/link'
import {
  DollarSign,
  Clock,
  MapPin,
  Smartphone,
  Trophy,
  Heart,
  ArrowRight,
  CheckCircle,
  Users,
  Building,
  Receipt,
  Calculator,
  Shirt,
  Megaphone,
  Sparkles,
  Film,
  Camera,
  Gift,
  Building2,
  Award,
  Star,
  FileText,
  Image as ImageIcon,
  Download,
  Mail,
  ScanLine,
  Route,
  Flame,
  Target,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { TierCard, getTierBenefits } from '@/components/flow/fund/TierCard'

const APP_STORE_URL =
  'https://apps.apple.com/us/app/global-community-cleaners/id6760203806'

type TabKey = 'general' | 'workers' | 'sponsors' | 'app' | 'bounties'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'general', label: 'General' },
  { key: 'workers', label: 'For Workers' },
  { key: 'sponsors', label: 'For Sponsors' },
  { key: 'app', label: 'App Features' },
  { key: 'bounties', label: 'Bounties' },
]

function isTabKey(value: string | null): value is TabKey {
  return !!value && TABS.some((tab) => tab.key === value)
}

function HowItWorksTabsInner() {
  const searchParams = useSearchParams()
  const initialTab = searchParams.get('tab')
  const [tab, setTab] = useState<TabKey>(
    isTabKey(initialTab) ? initialTab : 'general'
  )

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Hero */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-3xl">
          <h1 className="mb-4 text-4xl font-bold tracking-tight sm:text-5xl">
            How <span className="text-brand-green-600">GCC</span> Works
          </h1>
          <p className="mb-8 text-lg text-muted-foreground">
            One model — $100 funds a block, a neighbor cleans it, everyone can see the
            result. Here&apos;s how it works for you.
          </p>
        </div>

        {/* Tab Switcher */}
        <div className="mx-auto flex max-w-2xl flex-wrap justify-center gap-1 rounded-xl border border-neutral-100 bg-white p-1 shadow-sm">
          {TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`flex-1 min-w-[7rem] rounded-lg px-4 py-2.5 text-sm font-medium transition-all ${
                tab === t.key
                  ? 'bg-brand-green-500 text-white shadow-sm'
                  : 'text-neutral-600 hover:text-neutral-900'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </section>

      {tab === 'general' && <GeneralTab onNavigate={setTab} />}
      {tab === 'workers' && <WorkersTab />}
      {tab === 'sponsors' && <SponsorsTab />}
      {tab === 'app' && <AppFeaturesTab onNavigate={setTab} />}
      {tab === 'bounties' && <BountiesTab />}
    </div>
  )
}

export function HowItWorksTabs() {
  return (
    <Suspense fallback={null}>
      <HowItWorksTabsInner />
    </Suspense>
  )
}

// ============================================================================
// GENERAL
// ============================================================================

function GeneralTab({ onNavigate }: { onNavigate: (tab: TabKey) => void }) {
  return (
    <>
      <section className="section-container py-4">
        <div className="mx-auto max-w-3xl text-center">
          <p className="text-muted-foreground leading-relaxed">
            Global Community Cleaners is a community-cleanup marketplace. Sponsors fund
            city blocks for $100 each, local workers claim and clean those blocks, and
            GPS-verified before/after photos prove the work got done. We launched in
            North Lawndale, Chicago — and we&apos;re built to bring this model to every
            neighborhood.
          </p>
        </div>
      </section>

      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">How Your $100 Works</h2>
        <div className="mx-auto max-w-4xl">
          <div className="grid gap-4 md:grid-cols-3">
            <Card className="text-center">
              <CardContent className="pt-6">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-brand-green-100 text-2xl font-bold text-brand-green-600">
                  1
                </div>
                <h3 className="mb-2 font-semibold">You Sponsor</h3>
                <p className="text-sm text-muted-foreground">
                  Choose blocks to sponsor. Payment goes directly to our worker fund.
                </p>
              </CardContent>
            </Card>
            <Card className="text-center">
              <CardContent className="pt-6">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-brand-green-100 text-2xl font-bold text-brand-green-600">
                  2
                </div>
                <h3 className="mb-2 font-semibold">Worker Cleans</h3>
                <p className="text-sm text-muted-foreground">
                  A local worker claims and cleans the block with GPS tracking and photos.
                </p>
              </CardContent>
            </Card>
            <Card className="text-center">
              <CardContent className="pt-6">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-brand-blue-100 text-2xl font-bold text-brand-blue-600">
                  3
                </div>
                <h3 className="mb-2 font-semibold">Impact Verified</h3>
                <p className="text-sm text-muted-foreground">
                  You receive before/after photos and your tax receipt.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </section>

      <section className="bg-brand-green-50 py-12">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">Pick Your Path</h2>
          <div className="mx-auto grid max-w-4xl gap-6 sm:grid-cols-3">
            <Card className="border-0 bg-white text-center">
              <CardContent className="pt-6">
                <DollarSign className="mx-auto mb-3 h-8 w-8 text-brand-green-600" />
                <h3 className="mb-2 font-semibold">Earn as a Worker</h3>
                <p className="mb-4 text-sm text-muted-foreground">
                  $100 per block cleaned, flexible hours.
                </p>
                <Button variant="secondary" onClick={() => onNavigate('workers')}>
                  See Worker Details
                </Button>
              </CardContent>
            </Card>
            <Card className="border-0 bg-white text-center">
              <CardContent className="pt-6">
                <Heart className="mx-auto mb-3 h-8 w-8 text-brand-green-600" />
                <h3 className="mb-2 font-semibold">Fund as a Sponsor</h3>
                <p className="mb-4 text-sm text-muted-foreground">
                  100% tax-deductible, choose your blocks.
                </p>
                <Button variant="secondary" onClick={() => onNavigate('sponsors')}>
                  See Sponsor Details
                </Button>
              </CardContent>
            </Card>
            <Card className="border-0 bg-white text-center">
              <CardContent className="pt-6">
                <Smartphone className="mx-auto mb-3 h-8 w-8 text-brand-green-600" />
                <h3 className="mb-2 font-semibold">Explore the App</h3>
                <p className="mb-4 text-sm text-muted-foreground">
                  Scanning, the cleanliness map, points, and more.
                </p>
                <Button variant="secondary" onClick={() => onNavigate('app')}>
                  See App Features
                </Button>
              </CardContent>
            </Card>
          </div>
        </div>
      </section>
    </>
  )
}

// ============================================================================
// WORKERS
// ============================================================================

const workerBenefits = [
  {
    icon: DollarSign,
    title: '$100 Per Block',
    description:
      'Earn a guaranteed $100 for each block you clean. No complicated pay structures - just complete the job and get paid.',
  },
  {
    icon: Clock,
    title: 'Flexible Schedule',
    description:
      'Work when you want. Claim jobs that fit your schedule. Morning, afternoon, or weekend - you decide.',
  },
  {
    icon: Smartphone,
    title: 'Easy App Experience',
    description:
      'Our mobile-friendly app guides you through each job. GPS tracking, photo uploads, and instant verification.',
  },
  {
    icon: MapPin,
    title: 'Work in Your Neighborhood',
    description:
      'Clean blocks right in North Lawndale. No long commutes - make a difference where you live.',
  },
  {
    icon: Trophy,
    title: 'Achievements & Leaderboards',
    description:
      'Earn badges, climb the leaderboard, and get recognized for your contributions to the community.',
  },
  {
    icon: Heart,
    title: 'Meaningful Impact',
    description:
      "You're not just cleaning streets - you're transforming your community and creating a safer, cleaner environment for everyone.",
  },
]

const earningsExamples = [
  { blocks: 1, perWeek: '$100', perMonth: '$400' },
  { blocks: 2, perWeek: '$200', perMonth: '$800' },
  { blocks: 5, perWeek: '$500', perMonth: '$2,000' },
  { blocks: 10, perWeek: '$1,000', perMonth: '$4,000' },
]

const workerRequirements = [
  'Must be 18 years or older',
  'Live in or near our launch area — North Lawndale, Chicago (60623, 60624)',
  'Valid government ID',
  'Smartphone with camera',
  'Ability to walk and perform light physical work',
]

function WorkersTab() {
  return (
    <>
      <section className="section-container py-4 text-center">
        <div className="mx-auto max-w-2xl">
          <p className="text-lg text-muted-foreground">
            Turn your time into money while making North Lawndale cleaner. No
            experience needed - just your commitment to our community.
          </p>
          <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/worker/signup">
                Start Earning Today
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="secondary" size="lg" asChild>
              <Link href="/join">Join Waitlist</Link>
            </Button>
          </div>
        </div>
      </section>

      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">Why Clean With GCC?</h2>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
          {workerBenefits.map((benefit) => {
            const Icon = benefit.icon
            return (
              <Card key={benefit.title} className="border-0 bg-white/80 backdrop-blur">
                <CardHeader className="pb-2">
                  <div className="mb-2 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100">
                    <Icon className="h-6 w-6 text-brand-green-600" />
                  </div>
                  <CardTitle className="text-lg">{benefit.title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground">{benefit.description}</p>
                </CardContent>
              </Card>
            )
          })}
        </div>
      </section>

      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">How Much Can You Earn?</h2>
          <div className="mx-auto max-w-2xl overflow-hidden rounded-2xl bg-white/10 backdrop-blur">
            <table className="w-full">
              <thead>
                <tr className="border-b border-white/20">
                  <th className="px-6 py-4 text-left text-sm font-semibold">
                    Blocks/Week
                  </th>
                  <th className="px-6 py-4 text-right text-sm font-semibold">Weekly</th>
                  <th className="px-6 py-4 text-right text-sm font-semibold">Monthly*</th>
                </tr>
              </thead>
              <tbody>
                {earningsExamples.map((example) => (
                  <tr
                    key={example.blocks}
                    className="border-b border-white/10 last:border-0"
                  >
                    <td className="px-6 py-4 text-sm">
                      {example.blocks} {example.blocks === 1 ? 'block' : 'blocks'}
                    </td>
                    <td className="px-6 py-4 text-right text-lg font-bold text-brand-green-300">
                      {example.perWeek}
                    </td>
                    <td className="px-6 py-4 text-right text-lg font-bold text-brand-green-300">
                      {example.perMonth}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="border-t border-white/20 px-6 py-3 text-center text-xs text-white/60">
              *Based on 4 weeks. Actual earnings depend on block availability.
            </p>
          </div>
        </div>
      </section>

      <section className="section-container py-16">
        <div className="mx-auto max-w-2xl">
          <h2 className="mb-8 text-center text-2xl font-bold">What You Need</h2>
          <Card>
            <CardContent className="p-6">
              <ul className="space-y-4">
                {workerRequirements.map((req) => (
                  <li key={req} className="flex items-start gap-3">
                    <CheckCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-brand-green-500" />
                    <span>{req}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            Outside North Lawndale?{' '}
            <Link href="/join" className="font-semibold text-brand-green-700 hover:underline">
              Join the waitlist
            </Link>{' '}
            — we&apos;ll open your zip code next.
          </p>
        </div>
      </section>

      <section className="bg-brand-green-50 py-16">
        <div className="section-container text-center">
          <h2 className="mb-4 text-2xl font-bold">Ready to Start Earning?</h2>
          <p className="mb-8 text-muted-foreground">
            Join the GCC workforce and make a real difference in North Lawndale.
          </p>
          <Button size="lg" asChild>
            <Link href="/worker/signup">
              Apply Now
              <ArrowRight className="ml-2 h-5 w-5" />
            </Link>
          </Button>
        </div>
      </section>
    </>
  )
}

// ============================================================================
// SPONSORS
// ============================================================================

const addOns = [
  {
    icon: Shirt,
    title: 'Worker Branding',
    description: "Put your logo on workers' vests, shirts, and hats as they clean.",
    prices: ['Vests $15/block', 'T-Shirts $12/block', 'Hats $8/block'],
  },
  {
    icon: Megaphone,
    title: 'Street Advertising',
    description: 'Block signs, banners, and sidewalk stencils with your branding.',
    prices: ['Signs $25/block', 'Banners $50/block', 'Chalk Art $100/block'],
  },
  {
    icon: Sparkles,
    title: 'Enhanced Cleaning',
    description: 'Deep cleaning, pressure washing, graffiti removal, and more.',
    prices: ['Deep Clean +$50', 'Pressure Wash +$150'],
  },
  {
    icon: Camera,
    title: 'Photography',
    description: 'Professional photo packages to capture your impact for marketing.',
    prices: ['Basic $500', 'Premium $3,500'],
  },
  {
    icon: Film,
    title: 'Video Production',
    description: 'From social clips to full documentaries showcasing your partnership.',
    prices: ['Social $1,000', 'Documentary $35,000'],
  },
  {
    icon: Gift,
    title: 'Event Packages',
    description: 'Cleanup crews for block parties, grand openings, and corporate events.',
    prices: ['Pop-Up $300', 'Large Event $1,500'],
  },
]

const enterpriseTiers = [
  {
    icon: Award,
    name: 'Community Champion',
    price: '$25k - $49k',
    scope: '250-500 blocks',
    perks: ['Full branded campaign', 'Quarterly impact reports', 'Dedicated account manager'],
  },
  {
    icon: Star,
    name: 'Impact Partner',
    price: '$50k - $99k',
    scope: 'Neighborhood adoption',
    perks: ['Photo + video package', 'Press release support', 'Named block/corner option'],
  },
  {
    icon: Trophy,
    name: 'Movement Partner',
    price: '$250k - $499k',
    scope: 'Multi-neighborhood',
    perks: ['Full documentary crew', 'Named initiative', 'Speaking opportunities'],
    popular: true,
  },
  {
    icon: Building2,
    name: 'Founding Partner',
    price: '$1M+',
    scope: 'Movement-defining',
    perks: ['Feature documentary', 'Co-branding rights', 'Historic recognition'],
  },
]

function SponsorsTab() {
  return (
    <>
      <section className="section-container py-4 text-center">
        <div className="mx-auto max-w-2xl">
          <p className="text-lg text-muted-foreground">
            For just $100, you can sponsor the cleaning of one city block while
            providing meaningful employment to local residents.
          </p>
          <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/sponsor">
                Sponsor a Block ($100)
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="outline" size="lg" asChild>
              <Link href="/sponsor/calculator">Build Custom Package</Link>
            </Button>
          </div>
          <div className="mt-4">
            <Link
              href="/sponsor/wall"
              className="text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              View our sponsors →
            </Link>
          </div>
        </div>
      </section>

      {/* What You'll Receive */}
      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">What You&apos;ll Receive</h2>
        <div className="mx-auto max-w-4xl">
          <div className="grid gap-6 md:grid-cols-2">
            <Card className="border-2 border-brand-green-200 bg-brand-green-50">
              <CardContent className="pt-6">
                <div className="flex items-start gap-4">
                  <div className="flex h-14 w-14 flex-shrink-0 items-center justify-center rounded-xl bg-brand-green-600 text-white">
                    <ImageIcon className="h-7 w-7" />
                  </div>
                  <div>
                    <h3 className="mb-2 text-lg font-semibold">Before & After Photos</h3>
                    <p className="mb-3 text-sm text-muted-foreground">
                      Every sponsored block includes verified before and after photos
                      taken by the worker, showing the real impact of your donation.
                    </p>
                    <ul className="space-y-1 text-sm text-muted-foreground">
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-green-600" />
                        Timestamped & GPS-verified
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-green-600" />
                        Sent directly to your email
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-green-600" />
                        Shareable on social media
                      </li>
                    </ul>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="border-2 border-brand-green-200 bg-brand-green-50">
              <CardContent className="pt-6">
                <div className="flex items-start gap-4">
                  <div className="flex h-14 w-14 flex-shrink-0 items-center justify-center rounded-xl bg-brand-green-600 text-white">
                    <FileText className="h-7 w-7" />
                  </div>
                  <div>
                    <h3 className="mb-2 text-lg font-semibold">PDF Donation Receipt</h3>
                    <p className="mb-3 text-sm text-muted-foreground">
                      Receive a downloadable PDF receipt documenting your charitable
                      donation for tax purposes. GCC is a registered 501(c)(3) nonprofit.
                    </p>
                    <ul className="space-y-1 text-sm text-muted-foreground">
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-green-600" />
                        100% tax-deductible
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-green-600" />
                        Instant download link
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-green-600" />
                        Includes block details & date
                      </li>
                    </ul>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>

          <div className="mt-8 rounded-xl bg-muted/50 p-6">
            <h3 className="mb-4 text-center font-semibold">
              Also Included With Every Sponsorship
            </h3>
            <div className="grid gap-4 text-center sm:grid-cols-2 lg:grid-cols-4">
              <div className="flex flex-col items-center gap-2">
                <Mail className="h-6 w-6 text-brand-green-600" />
                <span className="text-sm font-medium">Email Confirmation</span>
                <span className="text-xs text-muted-foreground">Instant receipt</span>
              </div>
              <div className="flex flex-col items-center gap-2">
                <MapPin className="h-6 w-6 text-brand-green-600" />
                <span className="text-sm font-medium">Block Location</span>
                <span className="text-xs text-muted-foreground">Map & address</span>
              </div>
              <div className="flex flex-col items-center gap-2">
                <Users className="h-6 w-6 text-brand-green-600" />
                <span className="text-sm font-medium">Worker Info</span>
                <span className="text-xs text-muted-foreground">Name & photo</span>
              </div>
              <div className="flex flex-col items-center gap-2">
                <Download className="h-6 w-6 text-brand-green-600" />
                <span className="text-sm font-medium">Impact Summary</span>
                <span className="text-xs text-muted-foreground">Downloadable report</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Transparent Pricing */}
      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container">
          <div className="mx-auto max-w-4xl">
            <div className="mb-6 flex items-center justify-center gap-3">
              <div className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-green-700">
                <Receipt className="h-6 w-6 text-brand-green-300" />
              </div>
            </div>
            <h2 className="mb-4 text-center text-3xl font-bold">Transparent Pricing</h2>
            <p className="mb-8 text-center text-xl text-white/90">
              $100 donated = $100 to the worker
            </p>

            <div className="rounded-2xl bg-white/10 p-8 backdrop-blur">
              <div className="grid gap-6 text-center md:grid-cols-3">
                <div>
                  <p className="text-4xl font-bold text-brand-green-300">100%</p>
                  <p className="mt-2 text-white/80">
                    of base block sponsorship goes directly to workers
                  </p>
                </div>
                <div>
                  <p className="text-4xl font-bold text-brand-green-400">$0</p>
                  <p className="mt-2 text-white/80">platform fees on basic sponsorships</p>
                </div>
                <div>
                  <p className="text-4xl font-bold text-white">Add-ons</p>
                  <p className="mt-2 text-white/80">
                    fund GCC operations, equipment, and services
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Amplify Your Impact */}
      <section className="section-container py-16">
        <div className="mb-12 text-center">
          <h2 className="mb-4 text-3xl font-bold">Amplify Your Impact</h2>
          <p className="mx-auto max-w-2xl text-lg text-muted-foreground">
            Go beyond basic sponsorship with branded campaigns, enhanced services, and
            media production
          </p>
        </div>

        <div className="mb-10 grid gap-6 md:grid-cols-2 lg:grid-cols-3">
          {addOns.map((addOn) => {
            const Icon = addOn.icon
            return (
              <Card
                key={addOn.title}
                className="border-2 border-transparent transition-colors hover:border-brand-green-200"
              >
                <CardHeader className="pb-2">
                  <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100">
                    <Icon className="h-6 w-6 text-brand-green-600" />
                  </div>
                  <CardTitle className="text-lg">{addOn.title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="mb-4 text-sm text-muted-foreground">
                    {addOn.description}
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {addOn.prices.map((price) => (
                      <span
                        key={price}
                        className="rounded bg-brand-green-50 px-2 py-1 text-xs text-brand-green-700"
                      >
                        {price}
                      </span>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>

        <div className="text-center">
          <Button size="lg" asChild>
            <Link href="/sponsor/calculator">
              Build Your Custom Package
              <ArrowRight className="ml-2 h-5 w-5" />
            </Link>
          </Button>
        </div>
      </section>

      {/* Enterprise Partnerships */}
      <section className="bg-gradient-to-br from-brand-green-900 to-brand-green-800 py-16 text-white">
        <div className="section-container">
          <div className="mb-12 text-center">
            <div className="mb-6 inline-flex items-center gap-2 rounded-full bg-brand-green-500/20 px-4 py-2">
              <Building2 className="h-4 w-4 text-brand-green-400" />
              <span className="text-sm font-medium text-brand-green-400">$25,000+</span>
            </div>
            <h2 className="mb-4 text-3xl font-bold">Enterprise Partnerships</h2>
            <p className="mx-auto max-w-2xl text-lg text-white/80">
              Transform your sponsorship into a movement with multi-neighborhood
              campaigns, documentary production, and dedicated account management.
            </p>
          </div>

          <div className="mb-12 grid gap-6 md:grid-cols-2 lg:grid-cols-4">
            {enterpriseTiers.map((t) => {
              const Icon = t.icon
              return (
                <div
                  key={t.name}
                  className={`relative rounded-xl border p-6 ${
                    t.popular
                      ? 'border-brand-green-500/50 bg-white/10'
                      : 'border-white/10 bg-white/5'
                  }`}
                >
                  {t.popular && (
                    <div className="absolute -top-3 right-4 rounded-full bg-brand-green-500 px-3 py-1 text-xs font-bold text-brand-green-900">
                      Popular
                    </div>
                  )}
                  <div className="mb-4 flex items-center gap-2">
                    <Icon className="h-5 w-5 text-brand-green-400" />
                    <span className="font-semibold">{t.name}</span>
                  </div>
                  <p className="mb-2 text-2xl font-bold text-brand-green-400">{t.price}</p>
                  <p className="mb-4 text-sm text-white/60">{t.scope}</p>
                  <ul className="space-y-2 text-sm text-white/80">
                    {t.perks.map((perk) => (
                      <li key={perk} className="flex items-start gap-2">
                        <CheckCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-brand-green-400" />
                        {perk}
                      </li>
                    ))}
                  </ul>
                </div>
              )
            })}
          </div>

          <div className="text-center">
            <Button
              size="lg"
              variant="secondary"
              className="bg-brand-green-500 text-brand-green-900 hover:bg-brand-green-400"
              asChild
            >
              <Link href="/sponsor/enterprise">
                Explore Enterprise Partnerships
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
          </div>
        </div>
      </section>

      {/* Sponsor Tiers */}
      <section className="section-container py-16">
        <h2 className="mb-8 text-center text-2xl font-bold">Sponsorship Tiers</h2>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <TierCard
            tier="bronze"
            blockCount={1}
            totalAmount={10000}
            benefits={getTierBenefits('bronze').slice(0, 3)}
          />
          <TierCard
            tier="silver"
            blockCount={5}
            totalAmount={50000}
            benefits={getTierBenefits('silver').slice(0, 4)}
          />
          <TierCard
            tier="gold"
            blockCount={20}
            totalAmount={200000}
            benefits={getTierBenefits('gold').slice(0, 5)}
            isPopular
          />
          <TierCard
            tier="platinum"
            blockCount={50}
            totalAmount={500000}
            benefits={getTierBenefits('platinum').slice(0, 6)}
          />
        </div>
      </section>

      {/* Tax Calculator */}
      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container">
          <div className="mx-auto max-w-2xl text-center">
            <Calculator className="mx-auto mb-4 h-12 w-12 text-brand-green-300" />
            <h2 className="mb-4 text-2xl font-bold">Tax Benefits Calculator</h2>
            <p className="mb-6 text-white/80">
              Your sponsorship is 100% tax-deductible. For example:
            </p>
            <div className="rounded-xl bg-white/10 p-6 backdrop-blur">
              <div className="grid gap-4 text-left sm:grid-cols-3">
                <div>
                  <p className="text-sm text-white/60">Sponsorship</p>
                  <p className="text-2xl font-bold">$500</p>
                </div>
                <div>
                  <p className="text-sm text-white/60">Tax Bracket (25%)</p>
                  <p className="text-2xl font-bold text-brand-green-300">-$125</p>
                </div>
                <div>
                  <p className="text-sm text-white/60">Net Cost</p>
                  <p className="text-2xl font-bold">$375</p>
                </div>
              </div>
            </div>
            <p className="mt-4 text-sm text-white/60">
              *Tax savings vary by individual situation. Consult your tax advisor.
            </p>
          </div>
        </div>
      </section>

      {/* Final CTA */}
      <section className="section-container py-16 text-center">
        <h2 className="mb-4 text-2xl font-bold">Ready to Make an Impact?</h2>
        <p className="mx-auto mb-8 max-w-xl text-muted-foreground">
          Join over 100 sponsors who have already funded cleaner streets in North
          Lawndale. Start with a single block or build a custom campaign.
        </p>
        <div className="flex flex-col justify-center gap-4 sm:flex-row">
          <Button size="lg" asChild>
            <Link href="/sponsor">
              Sponsor a Block ($100)
              <ArrowRight className="ml-2 h-5 w-5" />
            </Link>
          </Button>
          <Button size="lg" variant="outline" asChild>
            <Link href="/sponsor/calculator">Build Custom Package</Link>
          </Button>
        </div>
      </section>
    </>
  )
}

// ============================================================================
// APP FEATURES
// ============================================================================

const appFeatures = [
  {
    icon: ScanLine,
    title: 'GPS-Verified Scanning',
    description:
      'Workers photograph blocks before and after cleanup. Every photo is GPS-tagged and timestamped, so completed work is verifiable, not just claimed.',
  },
  {
    icon: MapPin,
    title: 'Live Cleanliness Map',
    description:
      'A real-time heatmap of the service area shows which blocks are clean, in progress, or need attention, along with reported spots and active bounties.',
  },
  {
    icon: Route,
    title: 'Route Fulfillment',
    description:
      'Workers can follow guided routes with checkpoints, capturing a required photo at each stop until the whole route is complete.',
  },
  {
    icon: Target,
    title: 'Spot Reporting',
    description:
      'Anyone can report a dirty spot with a photo and location. Reports can turn into bounties that a worker claims and clears.',
  },
  {
    icon: Flame,
    title: 'Points & Gamification',
    description:
      'Cleanups, verified spot reports, and completed routes earn points. Badges and leaderboards recognize the most active community members.',
  },
  {
    icon: Trophy,
    title: 'Achievements & Leaderboards',
    description:
      'Track your standing in the community leaderboard and unlock recognition as you contribute more cleanups and reports.',
  },
]

function AppFeaturesTab({ onNavigate }: { onNavigate: (tab: TabKey) => void }) {
  return (
    <>
      <section className="section-container py-4 text-center">
        <div className="mx-auto max-w-2xl">
          <p className="text-lg text-muted-foreground">
            The GCC app is how sponsors, workers, and neighbors see and drive the work —
            live, verified, on a map.
          </p>
          <div className="mt-6 flex flex-col items-center justify-center gap-4 sm:flex-row">
            <Link
              href={APP_STORE_URL}
              className="inline-flex min-w-[220px] items-center justify-center gap-3 rounded-2xl bg-black px-6 py-3.5 font-semibold text-white shadow-lg transition-colors hover:bg-neutral-800"
              aria-label="Download GCC on the App Store"
            >
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="currentColor">
                <path d="M17.05 20.28c-.98.95-2.05.8-3.08.35-1.09-.46-2.09-.48-3.24 0-1.44.62-2.2.44-3.06-.35C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
              </svg>
              <div className="flex flex-col items-start leading-none">
                <span className="text-xs opacity-80">Download on the</span>
                <span className="text-base font-semibold">App Store</span>
              </div>
            </Link>
            <div className="inline-flex min-w-[220px] items-center justify-center gap-3 rounded-2xl border border-dashed border-neutral-300 px-6 py-3.5 font-semibold text-neutral-500">
              Android — coming soon
            </div>
          </div>
        </div>
      </section>

      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">What the App Does</h2>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
          {appFeatures.map((feature) => {
            const Icon = feature.icon
            return (
              <Card key={feature.title} className="border-0 bg-white/80 backdrop-blur">
                <CardHeader className="pb-2">
                  <div className="mb-2 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100">
                    <Icon className="h-6 w-6 text-brand-green-600" />
                  </div>
                  <CardTitle className="text-lg">{feature.title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground">{feature.description}</p>
                </CardContent>
              </Card>
            )
          })}
        </div>
      </section>

      <section className="bg-brand-green-50 py-16">
        <div className="section-container text-center">
          <h2 className="mb-4 text-2xl font-bold">See It in Action</h2>
          <p className="mb-8 text-muted-foreground">
            The bounty map is the clearest way to see the app&apos;s live data — open
            spots, active bounties, and cleaned blocks, all on one map.
          </p>
          <Button size="lg" onClick={() => onNavigate('bounties')}>
            View Bounties Tab
            <ArrowRight className="ml-2 h-5 w-5" />
          </Button>
        </div>
      </section>
    </>
  )
}

// ============================================================================
// BOUNTIES
// ============================================================================

function BountiesTab() {
  return (
    <section className="section-container py-4">
      <div className="mx-auto max-w-3xl text-center">
        <h2 className="mb-4 text-2xl font-bold">Community Bounties</h2>
        <p className="mb-8 text-muted-foreground">
          See something dirty? Post a bounty. Someone in the community claims it and
          cleans it up — a peer-to-peer system that puts cleanup power in your hands.
          GPS-verified cleanups ensure bounties only pay out when the job is actually
          done.
        </p>

        <div className="mb-10 grid gap-6 sm:grid-cols-3">
          <Card className="border-0 bg-white/80 text-center backdrop-blur">
            <CardContent className="pt-6">
              <DollarSign className="mx-auto mb-3 h-8 w-8 text-red-500" />
              <h3 className="mb-1 font-semibold">Post a Bounty</h3>
              <p className="text-sm text-muted-foreground">
                Fund the cleanup of a spot you&apos;ve spotted.
              </p>
            </CardContent>
          </Card>
          <Card className="border-0 bg-white/80 text-center backdrop-blur">
            <CardContent className="pt-6">
              <MapPin className="mx-auto mb-3 h-8 w-8 text-orange-500" />
              <h3 className="mb-1 font-semibold">Browse the Live Map</h3>
              <p className="text-sm text-muted-foreground">
                See open bounties, spot reports, and blocks near you.
              </p>
            </CardContent>
          </Card>
          <Card className="border-0 bg-white/80 text-center backdrop-blur">
            <CardContent className="pt-6">
              <CheckCircle className="mx-auto mb-3 h-8 w-8 text-green-500" />
              <h3 className="mb-1 font-semibold">Claim & Clean</h3>
              <p className="text-sm text-muted-foreground">
                Workers claim bounties and get paid once verified.
              </p>
            </CardContent>
          </Card>
        </div>

        <Button
          size="lg"
          className="bg-red-600 text-white hover:bg-red-700"
          asChild
        >
          <Link href="/bounties">
            Open the Live Bounty Map
            <ArrowRight className="ml-2 h-5 w-5" />
          </Link>
        </Button>
      </div>
    </section>
  )
}
