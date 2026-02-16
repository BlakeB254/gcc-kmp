import { Metadata } from 'next'
import Link from 'next/link'
import {
  Heart,
  Receipt,
  Users,
  MapPin,
  Trophy,
  Building,
  ArrowRight,
  CheckCircle,
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
  Image,
  Download,
  Mail,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { TierCard } from '@/components/flow/fund/TierCard'
import { getTierBenefits } from '@/lib/sponsor-tiers'

export const metadata: Metadata = {
  title: 'Sponsor a Block | GCC',
  description:
    'Fund neighborhood cleaning for $100 per block. 100% tax-deductible. Make a visible impact in North Lawndale.',
}

const benefits = [
  {
    icon: Receipt,
    title: '100% Tax Deductible',
    description:
      "Every dollar you donate is tax-deductible. You'll receive a receipt for your records.",
  },
  {
    icon: Users,
    title: 'Employ Local Workers',
    description:
      'Your sponsorship directly pays North Lawndale residents to clean their own community.',
  },
  {
    icon: MapPin,
    title: 'Choose Your Blocks',
    description:
      'Select specific blocks to sponsor. Watch your blocks get cleaned and see the results.',
  },
  {
    icon: Trophy,
    title: 'Public Recognition',
    description:
      'Get featured on our sponsor wall, social media, and annual impact reports.',
  },
  {
    icon: Building,
    title: 'Corporate Programs',
    description:
      'Employee matching, team volunteer days, and branded sponsorship packages available.',
  },
  {
    icon: Heart,
    title: 'Real Impact',
    description:
      'Track exactly where your money goes. Photos, GPS verification, and impact metrics.',
  },
]

export default function SponsorBenefitsPage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Hero Section */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-3xl">
          <h1 className="mb-4 text-4xl font-bold tracking-tight sm:text-5xl">
            Fund a <span className="text-brand-green-600">Cleaner</span> Neighborhood
          </h1>
          <p className="mb-8 text-lg text-muted-foreground">
            For just $100, you can sponsor the cleaning of one city block while
            providing meaningful employment to local residents.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/sponsor">
                Sponsor a Block ($100)
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="outline" size="lg" asChild>
              <Link href="/sponsor/calculator">
                Build Custom Package
              </Link>
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

      {/* How It Works */}
      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">How Your $100 Works</h2>
        <div className="mx-auto max-w-4xl">
          <div className="grid gap-4 md:grid-cols-3">
            <Card className="text-center">
              <CardContent className="pt-6">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-brand-gold-100 text-2xl font-bold text-brand-gold-600">
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
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-brand-sky-100 text-2xl font-bold text-brand-sky-600">
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

      {/* What You'll Receive */}
      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">What You&apos;ll Receive</h2>
        <div className="mx-auto max-w-4xl">
          <div className="grid gap-6 md:grid-cols-2">
            {/* Before/After Photos */}
            <Card className="border-2 border-brand-green-200 bg-brand-green-50">
              <CardContent className="pt-6">
                <div className="flex items-start gap-4">
                  <div className="flex h-14 w-14 items-center justify-center rounded-xl bg-brand-green-600 text-white flex-shrink-0">
                    <Image className="h-7 w-7" />
                  </div>
                  <div>
                    <h3 className="mb-2 text-lg font-semibold">Before & After Photos</h3>
                    <p className="text-sm text-muted-foreground mb-3">
                      Every sponsored block includes verified before and after photos taken by the worker,
                      showing the real impact of your donation.
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

            {/* Tax Receipt PDF */}
            <Card className="border-2 border-brand-gold-200 bg-brand-gold-50">
              <CardContent className="pt-6">
                <div className="flex items-start gap-4">
                  <div className="flex h-14 w-14 items-center justify-center rounded-xl bg-brand-gold-600 text-white flex-shrink-0">
                    <FileText className="h-7 w-7" />
                  </div>
                  <div>
                    <h3 className="mb-2 text-lg font-semibold">PDF Donation Receipt</h3>
                    <p className="text-sm text-muted-foreground mb-3">
                      Receive a downloadable PDF receipt documenting your charitable donation
                      for tax purposes. GCC is a registered 501(c)(3) nonprofit.
                    </p>
                    <ul className="space-y-1 text-sm text-muted-foreground">
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-gold-600" />
                        100% tax-deductible
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-gold-600" />
                        Instant download link
                      </li>
                      <li className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-brand-gold-600" />
                        Includes block details & date
                      </li>
                    </ul>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>

          {/* Additional Deliverables */}
          <div className="mt-8 rounded-xl bg-muted/50 p-6">
            <h3 className="mb-4 text-center font-semibold">Also Included With Every Sponsorship</h3>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 text-center">
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
            <div className="flex items-center justify-center gap-3 mb-6">
              <div className="w-12 h-12 rounded-full bg-brand-green-700 flex items-center justify-center">
                <Heart className="w-6 h-6 text-brand-green-300" />
              </div>
            </div>
            <h2 className="text-center text-3xl font-bold mb-4">
              Transparent Pricing
            </h2>
            <p className="text-center text-xl text-white/90 mb-8">
              $100 donated = $100 to the worker
            </p>

            <div className="bg-white/10 rounded-2xl p-8 backdrop-blur">
              <div className="grid md:grid-cols-3 gap-6 text-center">
                <div>
                  <p className="text-4xl font-bold text-brand-green-300">100%</p>
                  <p className="text-white/80 mt-2">
                    of base block sponsorship goes directly to workers
                  </p>
                </div>
                <div>
                  <p className="text-4xl font-bold text-brand-gold-400">$0</p>
                  <p className="text-white/80 mt-2">
                    platform fees on basic sponsorships
                  </p>
                </div>
                <div>
                  <p className="text-4xl font-bold text-white">Add-ons</p>
                  <p className="text-white/80 mt-2">
                    fund GCC operations, equipment, and services
                  </p>
                </div>
              </div>

              <div className="border-t border-white/20 mt-8 pt-8">
                <p className="text-center text-white/70 mb-6">
                  Every invoice shows exactly where your money goes:
                </p>
                <div className="flex flex-wrap justify-center gap-4">
                  <div className="bg-brand-green-700/50 px-4 py-2 rounded-lg">
                    <span className="text-brand-green-300 font-semibold">Worker Payout</span>
                  </div>
                  <div className="bg-brand-gold-600/50 px-4 py-2 rounded-lg">
                    <span className="text-brand-gold-300 font-semibold">GCC Operations</span>
                  </div>
                  <div className="bg-white/10 px-4 py-2 rounded-lg">
                    <span className="text-white font-semibold">Total</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Amplify Your Impact - Add-ons Showcase */}
      <section className="section-container py-16">
        <div className="text-center mb-12">
          <h2 className="text-3xl font-bold mb-4">Amplify Your Impact</h2>
          <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
            Go beyond basic sponsorship with branded campaigns, enhanced services, and media production
          </p>
        </div>

        <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6 mb-10">
          {/* Worker Branding */}
          <Card className="border-2 border-transparent hover:border-brand-gold-200 transition-colors">
            <CardHeader className="pb-2">
              <div className="w-12 h-12 rounded-xl bg-brand-gold-100 flex items-center justify-center mb-3">
                <Shirt className="w-6 h-6 text-brand-gold-600" />
              </div>
              <CardTitle className="text-lg">Worker Branding</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                Put your logo on workers' vests, shirts, and hats as they clean.
              </p>
              <div className="flex flex-wrap gap-2">
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Vests $15/block</span>
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">T-Shirts $12/block</span>
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Hats $8/block</span>
              </div>
            </CardContent>
          </Card>

          {/* Street Advertising */}
          <Card className="border-2 border-transparent hover:border-brand-gold-200 transition-colors">
            <CardHeader className="pb-2">
              <div className="w-12 h-12 rounded-xl bg-brand-gold-100 flex items-center justify-center mb-3">
                <Megaphone className="w-6 h-6 text-brand-gold-600" />
              </div>
              <CardTitle className="text-lg">Street Advertising</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                Block signs, banners, and sidewalk stencils with your branding.
              </p>
              <div className="flex flex-wrap gap-2">
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Signs $25/block</span>
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Banners $50/block</span>
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Chalk Art $100/block</span>
              </div>
            </CardContent>
          </Card>

          {/* Enhanced Services */}
          <Card className="border-2 border-transparent hover:border-brand-gold-200 transition-colors">
            <CardHeader className="pb-2">
              <div className="w-12 h-12 rounded-xl bg-brand-green-100 flex items-center justify-center mb-3">
                <Sparkles className="w-6 h-6 text-brand-green-600" />
              </div>
              <CardTitle className="text-lg">Enhanced Cleaning</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                Deep cleaning, pressure washing, graffiti removal, and more.
              </p>
              <div className="flex flex-wrap gap-2">
                <span className="text-xs bg-brand-green-50 text-brand-green-700 px-2 py-1 rounded">Deep Clean +$50</span>
                <span className="text-xs bg-brand-green-50 text-brand-green-700 px-2 py-1 rounded">Pressure Wash +$150</span>
              </div>
            </CardContent>
          </Card>

          {/* Photography */}
          <Card className="border-2 border-transparent hover:border-brand-gold-200 transition-colors">
            <CardHeader className="pb-2">
              <div className="w-12 h-12 rounded-xl bg-brand-sky-100 flex items-center justify-center mb-3">
                <Camera className="w-6 h-6 text-brand-sky-600" />
              </div>
              <CardTitle className="text-lg">Photography</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                Professional photo packages to capture your impact for marketing.
              </p>
              <div className="flex flex-wrap gap-2">
                <span className="text-xs bg-brand-sky-50 text-brand-sky-700 px-2 py-1 rounded">Basic $500</span>
                <span className="text-xs bg-brand-sky-50 text-brand-sky-700 px-2 py-1 rounded">Premium $3,500</span>
              </div>
            </CardContent>
          </Card>

          {/* Video Production */}
          <Card className="border-2 border-transparent hover:border-brand-gold-200 transition-colors">
            <CardHeader className="pb-2">
              <div className="w-12 h-12 rounded-xl bg-brand-brown-100 flex items-center justify-center mb-3">
                <Film className="w-6 h-6 text-brand-brown-600" />
              </div>
              <CardTitle className="text-lg">Video Production</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                From social clips to full documentaries showcasing your partnership.
              </p>
              <div className="flex flex-wrap gap-2">
                <span className="text-xs bg-brand-brown-50 text-brand-brown-700 px-2 py-1 rounded">Social $1,000</span>
                <span className="text-xs bg-brand-brown-50 text-brand-brown-700 px-2 py-1 rounded">Documentary $35,000</span>
              </div>
            </CardContent>
          </Card>

          {/* Events */}
          <Card className="border-2 border-transparent hover:border-brand-gold-200 transition-colors">
            <CardHeader className="pb-2">
              <div className="w-12 h-12 rounded-xl bg-brand-gold-100 flex items-center justify-center mb-3">
                <Gift className="w-6 h-6 text-brand-gold-600" />
              </div>
              <CardTitle className="text-lg">Event Packages</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                Cleanup crews for block parties, grand openings, and corporate events.
              </p>
              <div className="flex flex-wrap gap-2">
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Pop-Up $300</span>
                <span className="text-xs bg-brand-gold-50 text-brand-gold-700 px-2 py-1 rounded">Large Event $1,500</span>
              </div>
            </CardContent>
          </Card>
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
      <section className="bg-gradient-to-br from-brand-brown-900 to-brand-brown-800 py-16 text-white">
        <div className="section-container">
          <div className="text-center mb-12">
            <div className="inline-flex items-center gap-2 bg-brand-gold-500/20 px-4 py-2 rounded-full mb-6">
              <Building2 className="w-4 h-4 text-brand-gold-400" />
              <span className="text-sm font-medium text-brand-gold-400">$25,000+</span>
            </div>
            <h2 className="text-3xl font-bold mb-4">Enterprise Partnerships</h2>
            <p className="text-lg text-white/80 max-w-2xl mx-auto">
              Transform your sponsorship into a movement with multi-neighborhood campaigns,
              documentary production, and dedicated account management.
            </p>
          </div>

          <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6 mb-12">
            {/* Community Champion */}
            <div className="bg-white/5 rounded-xl p-6 border border-white/10">
              <div className="flex items-center gap-2 mb-4">
                <Award className="w-5 h-5 text-brand-gold-400" />
                <span className="font-semibold">Community Champion</span>
              </div>
              <p className="text-2xl font-bold text-brand-gold-400 mb-2">$25k - $49k</p>
              <p className="text-sm text-white/60 mb-4">250-500 blocks</p>
              <ul className="space-y-2 text-sm text-white/80">
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Full branded campaign
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Quarterly impact reports
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Dedicated account manager
                </li>
              </ul>
            </div>

            {/* Impact Partner */}
            <div className="bg-white/5 rounded-xl p-6 border border-white/10">
              <div className="flex items-center gap-2 mb-4">
                <Star className="w-5 h-5 text-brand-gold-400" />
                <span className="font-semibold">Impact Partner</span>
              </div>
              <p className="text-2xl font-bold text-brand-gold-400 mb-2">$50k - $99k</p>
              <p className="text-sm text-white/60 mb-4">Neighborhood adoption</p>
              <ul className="space-y-2 text-sm text-white/80">
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Photo + video package
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Press release support
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Named block/corner option
                </li>
              </ul>
            </div>

            {/* Movement Partner */}
            <div className="bg-white/10 rounded-xl p-6 border border-brand-gold-500/50 relative">
              <div className="absolute -top-3 right-4 bg-brand-gold-500 text-brand-brown-900 text-xs font-bold px-3 py-1 rounded-full">
                Popular
              </div>
              <div className="flex items-center gap-2 mb-4">
                <Trophy className="w-5 h-5 text-brand-gold-400" />
                <span className="font-semibold">Movement Partner</span>
              </div>
              <p className="text-2xl font-bold text-brand-gold-400 mb-2">$250k - $499k</p>
              <p className="text-sm text-white/60 mb-4">Multi-neighborhood</p>
              <ul className="space-y-2 text-sm text-white/80">
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Full documentary crew
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Named initiative
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Speaking opportunities
                </li>
              </ul>
            </div>

            {/* Founding Partner */}
            <div className="bg-gradient-to-br from-brand-gold-500/20 to-brand-gold-500/5 rounded-xl p-6 border border-brand-gold-500/30">
              <div className="flex items-center gap-2 mb-4">
                <Building2 className="w-5 h-5 text-brand-gold-400" />
                <span className="font-semibold">Founding Partner</span>
              </div>
              <p className="text-2xl font-bold text-brand-gold-400 mb-2">$1M+</p>
              <p className="text-sm text-white/60 mb-4">Movement-defining</p>
              <ul className="space-y-2 text-sm text-white/80">
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Feature documentary
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Co-branding rights
                </li>
                <li className="flex items-start gap-2">
                  <CheckCircle className="w-4 h-4 text-brand-gold-400 flex-shrink-0 mt-0.5" />
                  Historic recognition
                </li>
              </ul>
            </div>
          </div>

          <div className="text-center">
            <Button size="lg" variant="secondary" className="bg-brand-gold-500 text-brand-brown-900 hover:bg-brand-gold-400" asChild>
              <Link href="/sponsor/enterprise">
                Explore Enterprise Partnerships
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
          </div>
        </div>
      </section>

      {/* Benefits Grid */}
      <section className="bg-brand-brown-50 py-16">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">
            Why Sponsor With GCC?
          </h2>
          <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
            {benefits.map((benefit) => {
              const Icon = benefit.icon
              return (
                <Card key={benefit.title} className="border-0 bg-white">
                  <CardHeader className="pb-2">
                    <div className="mb-2 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100">
                      <Icon className="h-6 w-6 text-brand-green-600" />
                    </div>
                    <CardTitle className="text-lg">{benefit.title}</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <p className="text-sm text-muted-foreground">
                      {benefit.description}
                    </p>
                  </CardContent>
                </Card>
              )
            })}
          </div>
        </div>
      </section>

      {/* Sponsor Tiers Preview */}
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
        <p className="mb-8 text-muted-foreground max-w-xl mx-auto">
          Join over 100 sponsors who have already funded cleaner streets in North Lawndale.
          Start with a single block or build a custom campaign.
        </p>
        <div className="flex flex-col sm:flex-row gap-4 justify-center">
          <Button size="lg" asChild>
            <Link href="/sponsor">
              Sponsor a Block ($100)
              <ArrowRight className="ml-2 h-5 w-5" />
            </Link>
          </Button>
          <Button size="lg" variant="outline" asChild>
            <Link href="/sponsor/calculator">
              Build Custom Package
            </Link>
          </Button>
        </div>
      </section>
    </div>
  )
}
