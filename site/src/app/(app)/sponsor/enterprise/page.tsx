'use client'

import { useState } from 'react'
import Link from 'next/link'
import {
  ChevronLeft,
  Building2,
  CheckCircle,
  Phone,
  Mail,
  Calendar,
  Award,
  Film,
  Users,
  MapPin,
  ArrowRight,
} from 'lucide-react'
import { Logo } from '@/components/brand/Logo'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

const enterpriseTiers = [
  {
    name: 'Community Champion',
    range: '$25,000 - $49,999',
    blocks: '250-500 blocks',
    benefits: [
      'Full branded campaign (vests, signs, equipment)',
      'Quarterly impact reports with metrics',
      'Sponsor wall "Gold" recognition',
      '2 social media features/month',
      'Basic photo package',
      'Dedicated account manager',
    ],
  },
  {
    name: 'Impact Partner',
    range: '$50,000 - $99,999',
    blocks: '500-1,000 blocks',
    // No "Most Popular" label until we have actual data — pre-launch this would
    // be misleading.
    popular: false,
    benefits: [
      'Everything in Community Champion',
      'Standard photo + video package',
      'Press release support',
      'Newsletter feature story',
      'Option to name a "block" or "corner"',
      'Monthly strategy calls',
    ],
  },
  {
    name: 'Movement Partner',
    range: '$250,000 - $499,999',
    blocks: 'Multi-neighborhood',
    benefits: [
      'Everything in Impact Partner',
      'Full documentary crew (5-10 min piece)',
      'Social media campaign management',
      'Named initiative in your brand',
      'Speaking opportunities at community events',
      'Press conference support',
    ],
  },
  {
    name: 'Founding Partner',
    range: '$1,000,000+',
    blocks: 'Movement-defining',
    benefits: [
      'Everything in Movement Partner',
      'Full feature documentary (20-30 min)',
      '"Presented by [Sponsor]" co-branding rights',
      'Exclusive neighborhood naming rights',
      'Historic recognition in all GCC materials',
      'Seat on GCC advisory board',
    ],
  },
]

export default function EnterprisePage() {
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    company: '',
    phone: '',
    budget: '',
    message: '',
  })
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isSubmitted, setIsSubmitted] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsSubmitting(true)

    // TODO: Submit to API
    await new Promise((resolve) => setTimeout(resolve, 1000))

    setIsSubmitted(true)
    setIsSubmitting(false)
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-900 to-brand-green-800">
      {/* Header */}
      <header className="bg-brand-green-900/50 backdrop-blur border-b border-white/10 sticky top-0 z-20">
        <div className="section-container py-4 flex items-center justify-between">
          <Link
            href="/sponsor"
            className="p-2 -ml-2 text-white/70 hover:text-white flex items-center gap-2"
          >
            <ChevronLeft className="w-5 h-5" />
            <Logo variant="short" size="sm" linkToHome={false} />
          </Link>
        </div>
      </header>

      <main className="section-container py-12">
        {/* Hero */}
        <div className="text-center mb-12">
          <div className="inline-flex items-center gap-2 bg-brand-green-500/20 px-4 py-2 rounded-full mb-6">
            <Building2 className="w-4 h-4 text-brand-green-400" />
            <span className="text-sm font-medium text-brand-green-400">Enterprise Partnerships</span>
          </div>
          <h1 className="text-4xl sm:text-5xl font-bold text-white mb-4">
            Transform Communities at Scale
          </h1>
          <p className="text-xl text-white/70 max-w-2xl mx-auto">
            Partner with GCC to create lasting impact across Chicago neighborhoods.
            100% of base sponsorship goes directly to workers.
          </p>
        </div>

        {/* Key Stats */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-16">
          {[
            { icon: Users, label: 'Workers Employed', value: '500+' },
            { icon: MapPin, label: 'Blocks Cleaned', value: '10,000+' },
            { icon: Award, label: 'Tax Deductible', value: '100%' },
            { icon: Film, label: 'Documentary Options', value: 'Full' },
          ].map((stat) => (
            <div
              key={stat.label}
              className="bg-white/5 border border-white/10 rounded-xl p-4 text-center"
            >
              <stat.icon className="w-6 h-6 text-brand-green-400 mx-auto mb-2" />
              <p className="text-2xl font-bold text-white">{stat.value}</p>
              <p className="text-sm text-white/60">{stat.label}</p>
            </div>
          ))}
        </div>

        {/* Tiers */}
        <div className="mb-16">
          <h2 className="text-2xl font-bold text-white text-center mb-8">
            Partnership Tiers
          </h2>
          <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6">
            {enterpriseTiers.map((tier) => (
              <Card
                key={tier.name}
                className={`bg-white/5 border-white/10 ${
                  tier.popular ? 'ring-2 ring-brand-green-500' : ''
                }`}
              >
                {tier.popular && (
                  <div className="bg-brand-green-500 text-brand-green-900 text-xs font-bold text-center py-1">
                    Most Popular
                  </div>
                )}
                <CardHeader>
                  <CardTitle className="text-white">{tier.name}</CardTitle>
                  <p className="text-2xl font-bold text-brand-green-400">{tier.range}</p>
                  <p className="text-sm text-white/60">{tier.blocks}</p>
                </CardHeader>
                <CardContent>
                  <ul className="space-y-2">
                    {tier.benefits.map((benefit, i) => (
                      <li key={i} className="flex items-start gap-2 text-sm text-white/80">
                        <CheckCircle className="w-4 h-4 text-brand-green-400 flex-shrink-0 mt-0.5" />
                        {benefit}
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            ))}
          </div>
        </div>

        {/* Contact Form */}
        <div className="max-w-2xl mx-auto">
          <Card className="bg-white border-0">
            <CardHeader className="text-center">
              <CardTitle className="text-2xl">Start Your Partnership</CardTitle>
              <p className="text-muted-foreground">
                Tell us about your goals and we'll create a custom proposal
              </p>
            </CardHeader>
            <CardContent>
              {isSubmitted ? (
                <div className="text-center py-8">
                  <CheckCircle className="w-16 h-16 text-brand-green-500 mx-auto mb-4" />
                  <h3 className="text-xl font-semibold mb-2">Thank You!</h3>
                  <p className="text-muted-foreground mb-6">
                    We've received your inquiry and will be in touch within 24 hours.
                  </p>
                  <Link href="/sponsor" className="btn-primary inline-flex items-center gap-2">
                    Back to Sponsor Page
                    <ArrowRight className="w-4 h-4" />
                  </Link>
                </div>
              ) : (
                <form onSubmit={handleSubmit} className="space-y-4">
                  <div className="grid sm:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium mb-1">Your Name *</label>
                      <input
                        type="text"
                        required
                        value={formData.name}
                        onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                        className="input-field"
                        placeholder="John Smith"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Company *</label>
                      <input
                        type="text"
                        required
                        value={formData.company}
                        onChange={(e) => setFormData({ ...formData, company: e.target.value })}
                        className="input-field"
                        placeholder="Company Name"
                      />
                    </div>
                  </div>

                  <div className="grid sm:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium mb-1">Email *</label>
                      <input
                        type="email"
                        required
                        value={formData.email}
                        onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                        className="input-field"
                        placeholder="you@company.com"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Phone</label>
                      <input
                        type="tel"
                        value={formData.phone}
                        onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
                        className="input-field"
                        placeholder="(312) 555-0123"
                      />
                    </div>
                  </div>

                  <div>
                    <label className="block text-sm font-medium mb-1">Budget Range *</label>
                    <select
                      required
                      value={formData.budget}
                      onChange={(e) => setFormData({ ...formData, budget: e.target.value })}
                      className="input-field"
                    >
                      <option value="">Select a range...</option>
                      <option value="25k-50k">$25,000 - $49,999</option>
                      <option value="50k-100k">$50,000 - $99,999</option>
                      <option value="100k-250k">$100,000 - $249,999</option>
                      <option value="250k-500k">$250,000 - $499,999</option>
                      <option value="500k-1m">$500,000 - $999,999</option>
                      <option value="1m+">$1,000,000+</option>
                    </select>
                  </div>

                  <div>
                    <label className="block text-sm font-medium mb-1">Tell us about your goals</label>
                    <textarea
                      value={formData.message}
                      onChange={(e) => setFormData({ ...formData, message: e.target.value })}
                      className="input-field min-h-[120px]"
                      placeholder="What neighborhoods are you interested in? What's your timeline? Any specific branding or media needs?"
                    />
                  </div>

                  <Button
                    type="submit"
                    className="w-full py-4 text-lg"
                    disabled={isSubmitting}
                  >
                    {isSubmitting ? 'Sending...' : 'Request Custom Proposal'}
                  </Button>

                  <p className="text-xs text-center text-muted-foreground">
                    Or call us directly at{' '}
                    <a href="tel:+13125551234" className="text-brand-green-600 hover:underline">
                      (312) 555-1234
                    </a>
                  </p>
                </form>
              )}
            </CardContent>
          </Card>
        </div>

        {/* Back Link */}
        <div className="text-center mt-8">
          <Link href="/sponsor/benefits" className="text-white/60 hover:text-white transition-colors">
            ← View all sponsorship options
          </Link>
        </div>
      </main>
    </div>
  )
}
