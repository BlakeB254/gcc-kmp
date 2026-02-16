'use client'

import { useState } from 'react'
import {
  ChevronDown,
  ChevronUp,
  Users,
  Award,
  Building2,
  CheckCircle2,
  CreditCard,
  X,
  Loader2,
  Lock,
  Shield,
  Phone,
  Calendar,
  Mail,
} from 'lucide-react'
import {
  formatPrice,
  recognitionLevels,
  type SponsorPackageConfig,
  type PriceBreakdown,
} from '@/lib/sponsor-pricing'

interface QuoteSummaryProps {
  config: SponsorPackageConfig
  priceBreakdown: PriceBreakdown
}

interface ContactForm {
  name: string
  email: string
  phone: string
  company: string
  notes: string
}

type ModalMode = 'checkout' | 'walkthrough'

export function QuoteSummary({ config, priceBreakdown }: QuoteSummaryProps) {
  const [isExpanded, setIsExpanded] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [modalMode, setModalMode] = useState<ModalMode>('checkout')
  const [isProcessing, setIsProcessing] = useState(false)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)
  const [walkthroughSubmitted, setWalkthroughSubmitted] = useState(false)
  const [contactForm, setContactForm] = useState<ContactForm>({
    name: '',
    email: '',
    phone: '',
    company: '',
    notes: '',
  })

  const openCheckoutModal = () => {
    setModalMode('checkout')
    setShowModal(true)
    setCheckoutError(null)
  }

  const openWalkthroughModal = () => {
    setModalMode('walkthrough')
    setShowModal(true)
    setCheckoutError(null)
    setWalkthroughSubmitted(false)
  }

  const recognitionInfo = priceBreakdown.recognitionLevel
    ? recognitionLevels[priceBreakdown.recognitionLevel]
    : null

  // Stripe max is $999,999.99 - anything over needs contact
  const MAX_STRIPE_AMOUNT = 99999999
  const requiresContact = priceBreakdown.total > MAX_STRIPE_AMOUNT

  const handleCheckout = async () => {
    // Validate required fields
    if (!contactForm.name.trim()) {
      setCheckoutError('Please enter your name')
      return
    }
    if (!contactForm.email.trim()) {
      setCheckoutError('Please enter your email')
      return
    }
    // Basic email validation
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!emailRegex.test(contactForm.email)) {
      setCheckoutError('Please enter a valid email address')
      return
    }

    setIsProcessing(true)
    setCheckoutError(null)

    try {
      const response = await fetch('/api/sponsor-packages/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          config,
          name: contactForm.name.trim(),
          email: contactForm.email.trim().toLowerCase(),
          phone: contactForm.phone.trim() || undefined,
          company: contactForm.company.trim() || undefined,
          notes: contactForm.notes.trim() || undefined,
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        if (data.contactRequired) {
          setCheckoutError(
            `This package (${formatPrice(data.total)}) exceeds our online checkout limit. Please contact us at enterprise@globalcommunitycleaners.org for packages over $999,999.`
          )
        } else {
          throw new Error(data.error || 'Failed to create checkout session')
        }
        return
      }

      // Redirect to Stripe checkout
      if (data.checkoutUrl) {
        window.location.href = data.checkoutUrl
      } else if (data.demo) {
        window.location.href = '/sponsor/success?demo=true'
      }
    } catch (error) {
      setCheckoutError(error instanceof Error ? error.message : 'Failed to process checkout')
    } finally {
      setIsProcessing(false)
    }
  }

  const handleWalkthroughRequest = async () => {
    // Validate required fields
    if (!contactForm.name.trim()) {
      setCheckoutError('Please enter your name')
      return
    }
    if (!contactForm.email.trim()) {
      setCheckoutError('Please enter your email')
      return
    }
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!emailRegex.test(contactForm.email)) {
      setCheckoutError('Please enter a valid email address')
      return
    }

    setIsProcessing(true)
    setCheckoutError(null)

    try {
      const response = await fetch('/api/sponsor-packages/walkthrough', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          config,
          priceBreakdown: {
            total: priceBreakdown.total,
            workerPayoutTotal: priceBreakdown.workerPayoutTotal,
            addonsTotal: priceBreakdown.addonsTotal,
            isEnterprise: priceBreakdown.isEnterprise,
            enterpriseTier: priceBreakdown.enterpriseTier,
          },
          name: contactForm.name.trim(),
          email: contactForm.email.trim().toLowerCase(),
          phone: contactForm.phone.trim() || undefined,
          company: contactForm.company.trim() || undefined,
          notes: contactForm.notes.trim() || undefined,
        }),
      })

      if (!response.ok) {
        const data = await response.json()
        throw new Error(data.error || 'Failed to submit request')
      }

      setWalkthroughSubmitted(true)
    } catch (error) {
      setCheckoutError(error instanceof Error ? error.message : 'Failed to submit request')
    } finally {
      setIsProcessing(false)
    }
  }

  const hasAddons =
    priceBreakdown.workerBrandingTotal > 0 ||
    priceBreakdown.equipmentBrandingTotal > 0 ||
    priceBreakdown.streetAdvertisingTotal > 0 ||
    priceBreakdown.digitalPresenceTotal > 0 ||
    priceBreakdown.enhancedCleaningTotal > 0 ||
    priceBreakdown.seasonalServicesTotal > 0 ||
    priceBreakdown.greenServicesTotal > 0 ||
    priceBreakdown.businessEngagementTotal > 0 ||
    priceBreakdown.eventsTotal > 0 ||
    priceBreakdown.mediaTotal > 0

  return (
    <div className="bg-white rounded-2xl border-2 border-brand-gold-200 shadow-lg overflow-hidden">
      {/* Header */}
      <div className="bg-gradient-to-r from-brand-gold-500 to-amber-500 p-4 text-white">
        <h3 className="font-bold text-lg">Your Quote</h3>
        <p className="text-sm text-white/80">Live pricing as you build</p>
      </div>

      {/* Worker Payout Highlight */}
      <div className="p-4 bg-brand-green-50 border-b border-brand-green-100">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-brand-green-100 flex items-center justify-center">
            <Users className="w-5 h-5 text-brand-green-600" />
          </div>
          <div>
            <p className="text-sm text-brand-green-700">Goes to Workers</p>
            <p className="text-xl font-bold text-brand-green-800">
              {formatPrice(priceBreakdown.workerPayoutTotal)}
            </p>
          </div>
          <div className="ml-auto">
            <CheckCircle2 className="w-6 h-6 text-brand-green-500" />
          </div>
        </div>
        <p className="text-xs text-brand-green-600 mt-2">
          $100 per block × {config.blockCount} block{config.blockCount !== 1 ? 's' : ''} — always full amount to workers
        </p>
      </div>

      {/* Expandable Breakdown */}
      <div className="p-4">
        <button
          onClick={() => setIsExpanded(!isExpanded)}
          className="w-full flex items-center justify-between text-sm font-medium text-neutral-600 hover:text-neutral-900 transition-colors"
        >
          <span>Itemized Breakdown</span>
          {isExpanded ? (
            <ChevronUp className="w-4 h-4" />
          ) : (
            <ChevronDown className="w-4 h-4" />
          )}
        </button>

        {isExpanded && (
          <div className="mt-4 space-y-3 text-sm">
            {/* Base - Worker Pay (never discounted) */}
            <div className="flex justify-between">
              <span className="text-neutral-600">
                {config.blockCount} block{config.blockCount !== 1 ? 's' : ''} × {formatPrice(priceBreakdown.baseBlockPrice)}
              </span>
              <span className="font-medium">{formatPrice(priceBreakdown.baseTotal)}</span>
            </div>
            <p className="text-xs text-brand-green-600 -mt-1">
              ↳ 100% goes directly to workers (never discounted)
            </p>

            {/* Add-on Volume Discount */}
            {priceBreakdown.addonDiscount > 0 && priceBreakdown.addonDiscountAmount > 0 && (
              <div className="flex justify-between text-brand-green-600">
                <span>Add-on volume discount ({priceBreakdown.addonDiscount}%)</span>
                <span>-{formatPrice(priceBreakdown.addonDiscountAmount)}</span>
              </div>
            )}

            {/* Add-ons */}
            {hasAddons && (
              <>
                <div className="border-t pt-3 mt-3">
                  <p className="text-xs text-neutral-500 uppercase tracking-wider mb-2">Add-ons (savings applied here)</p>
                </div>

                {priceBreakdown.workerBrandingTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Worker Branding</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.workerBrandingTotal)}</span>
                  </div>
                )}
                {priceBreakdown.equipmentBrandingTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Equipment Branding</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.equipmentBrandingTotal)}</span>
                  </div>
                )}
                {priceBreakdown.streetAdvertisingTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Street Advertising</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.streetAdvertisingTotal)}</span>
                  </div>
                )}
                {priceBreakdown.digitalPresenceTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Digital Presence</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.digitalPresenceTotal)}</span>
                  </div>
                )}
                {priceBreakdown.enhancedCleaningTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Enhanced Cleaning</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.enhancedCleaningTotal)}</span>
                  </div>
                )}
                {priceBreakdown.seasonalServicesTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Seasonal Services</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.seasonalServicesTotal)}</span>
                  </div>
                )}
                {priceBreakdown.greenServicesTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Green Services</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.greenServicesTotal)}</span>
                  </div>
                )}
                {priceBreakdown.businessEngagementTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Business Engagement</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.businessEngagementTotal)}</span>
                  </div>
                )}
                {priceBreakdown.eventsTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Event Packages</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.eventsTotal)}</span>
                  </div>
                )}
                {priceBreakdown.mediaTotal > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Media Production</span>
                    <span className="font-medium">{formatPrice(priceBreakdown.mediaTotal)}</span>
                  </div>
                )}
              </>
            )}

            {/* Recurring Discount */}
            {priceBreakdown.recurringDiscount > 0 && (
              <div className="flex justify-between text-brand-green-600 border-t pt-3">
                <span>Recurring discount ({priceBreakdown.recurringDiscount}%)</span>
                <span>-{formatPrice(priceBreakdown.recurringDiscountAmount)}</span>
              </div>
            )}
          </div>
        )}

        {/* Total */}
        <div className="border-t mt-4 pt-4">
          <div className="flex justify-between items-end">
            <div>
              <span className="text-neutral-600 text-sm">Total</span>
              {config.isRecurring && (
                <span className="text-xs text-neutral-500 block">
                  /{config.recurringFrequency}
                </span>
              )}
            </div>
            <span className="text-3xl font-bold text-neutral-900">
              {formatPrice(priceBreakdown.total)}
            </span>
          </div>

          {/* GCC Operations */}
          {priceBreakdown.gccOperationsTotal > 0 && (
            <p className="text-xs text-neutral-500 mt-2">
              {formatPrice(priceBreakdown.gccOperationsTotal)} funds GCC operations & equipment
            </p>
          )}
        </div>

        {/* Recognition Level */}
        {recognitionInfo && (
          <div className="mt-4 p-3 bg-brand-gold-50 rounded-lg border border-brand-gold-200">
            <div className="flex items-center gap-2">
              <Award className="w-4 h-4 text-brand-gold-600" />
              <span className="text-sm font-medium text-brand-gold-700">
                {recognitionInfo.name} Recognition
              </span>
            </div>
            <div className="mt-1 text-xs text-brand-gold-600">
              {recognitionInfo.recognition.slice(0, 2).join(' • ')}
            </div>
          </div>
        )}

        {/* Enterprise Badge */}
        {priceBreakdown.isEnterprise && (
          <div className="mt-4 p-3 bg-brand-brown-50 rounded-lg border border-brand-brown-200">
            <div className="flex items-center gap-2">
              <Building2 className="w-4 h-4 text-brand-brown-600" />
              <span className="text-sm font-medium text-brand-brown-700">
                Enterprise Partnership
              </span>
            </div>
            <p className="mt-1 text-xs text-brand-brown-600">
              Dedicated support & exclusive benefits included
            </p>
          </div>
        )}
      </div>

      {/* CTA */}
      <div className="p-4 bg-neutral-50 border-t space-y-3">
        {requiresContact ? (
          // For amounts over Stripe limit, show contact + walkthrough options
          <>
            <a
              href={`mailto:enterprise@globalcommunitycleaners.org?subject=Enterprise%20Package%20Inquiry%20-%20${formatPrice(priceBreakdown.total)}&body=Package%20Details:%0A- Blocks: ${config.blockCount}%0A- Total: ${formatPrice(priceBreakdown.total)}%0A- Worker Payout: ${formatPrice(priceBreakdown.workerPayoutTotal)}`}
              className="w-full bg-gradient-to-r from-brand-brown-700 to-brand-brown-800 text-white font-bold py-4 rounded-xl flex items-center justify-center gap-2 hover:from-brand-brown-600 hover:to-brand-brown-700 transition-all"
            >
              <Mail className="w-5 h-5" />
              Contact for Enterprise Package
            </a>
            <button
              onClick={openWalkthroughModal}
              className="w-full bg-white border-2 border-brand-gold-400 text-brand-gold-700 font-semibold py-3 rounded-xl flex items-center justify-center gap-2 hover:bg-brand-gold-50 transition-all"
            >
              <Calendar className="w-4 h-4" />
              Schedule a Walkthrough Call
            </button>
            <p className="text-xs text-center text-neutral-500">
              Packages over $999,999 require direct coordination for payment processing
            </p>
          </>
        ) : (
          // Standard checkout + walkthrough option
          <>
            {/* Primary: Stripe checkout */}
            <button
              onClick={openCheckoutModal}
              className="w-full bg-gradient-to-r from-brand-gold-500 to-amber-500 text-white font-bold py-4 rounded-xl flex items-center justify-center gap-2 hover:from-brand-gold-400 hover:to-amber-400 transition-all shadow-lg hover:shadow-xl"
            >
              <CreditCard className="w-5 h-5" />
              Checkout — {formatPrice(priceBreakdown.total)}
            </button>

            {/* Secondary: Request walkthrough for larger packages */}
            {priceBreakdown.total >= 500000 && (
              <button
                onClick={openWalkthroughModal}
                className="w-full bg-white border-2 border-brand-gold-300 text-brand-gold-700 font-medium py-3 rounded-xl flex items-center justify-center gap-2 hover:bg-brand-gold-50 hover:border-brand-gold-400 transition-all"
              >
                <Phone className="w-4 h-4" />
                {priceBreakdown.isEnterprise
                  ? 'Talk to an Enterprise Specialist First'
                  : 'Request a Walkthrough Before Paying'}
              </button>
            )}
          </>
        )}

        {/* Stripe security badge */}
        <div className="flex items-center justify-center gap-2 text-xs text-neutral-500">
          <Lock className="w-3 h-3" />
          <span>Secured by</span>
          <svg className="h-4" viewBox="0 0 60 25" fill="currentColor">
            <path d="M59.64 14.28h-8.06c.19 1.93 1.6 2.55 3.2 2.55 1.64 0 2.96-.37 4.05-.95v3.32a8.33 8.33 0 0 1-4.56 1.1c-4.01 0-6.83-2.5-6.83-7.48 0-4.19 2.39-7.52 6.3-7.52 3.92 0 5.96 3.28 5.96 7.5 0 .4-.02 1.04-.06 1.48zm-3.67-3.14c0-1.93-.86-3.19-2.24-3.19-1.34 0-2.34 1.28-2.49 3.2h4.73zm-13.77.17c0-1.03-.47-1.82-1.49-1.82-.99 0-1.64.81-1.64 1.85 0 1.03.59 1.76 1.57 1.76 1.04 0 1.56-.68 1.56-1.8zm-6.15 8.04V5.73h3.97v1.3c.73-.99 1.72-1.54 3.08-1.54 2.71 0 4.69 2.3 4.69 6.4 0 4.29-2.02 6.7-4.86 6.7-1.16 0-2.03-.43-2.72-1.2v4.8l-4.16.06zm-8.47-8.04c0-1.03-.47-1.82-1.49-1.82-.99 0-1.64.81-1.64 1.85 0 1.03.59 1.76 1.57 1.76 1.04 0 1.56-.68 1.56-1.8zm-6.15 5.44V5.73h3.97v1.3c.73-.99 1.72-1.54 3.08-1.54 2.71 0 4.69 2.3 4.69 6.4 0 4.29-2.02 6.7-4.86 6.7-1.16 0-2.03-.43-2.72-1.2v1.37l-4.16-.01zM11.99 19.2l4.53-13.93H20l-4.9 13.93h-3.11zM0 11.2c0-4.46 2.57-5.7 5.49-5.7 1.75 0 3.32.56 4.24 1.12V10c-.88-.58-2.25-1.02-3.47-1.02-1.49 0-2.18.47-2.18 1.9v.08l6.16.01v3.22H4.07v.74c0 1.37.71 1.89 2.32 1.89 1.19 0 2.45-.4 3.41-.97v3.36c-.95.54-2.53 1.1-4.34 1.1C2.49 20.3 0 18.84 0 14.83v-3.63z"/>
          </svg>
        </div>

        {/* Security note */}
        <div className="flex items-center justify-center gap-1.5 text-xs text-neutral-400">
          <Shield className="w-3 h-3" />
          <span>256-bit SSL encryption • PCI compliant</span>
        </div>
      </div>

      {/* Checkout / Walkthrough Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-md w-full max-h-[90vh] overflow-y-auto">
            {/* Modal Header */}
            <div className="p-4 border-b flex items-center justify-between">
              <div>
                {modalMode === 'checkout' ? (
                  <>
                    <h3 className="text-lg font-bold text-neutral-900">Complete Sponsorship</h3>
                    <p className="text-sm text-neutral-500">Secure checkout via Stripe</p>
                  </>
                ) : (
                  <>
                    <h3 className="text-lg font-bold text-neutral-900">Request a Walkthrough</h3>
                    <p className="text-sm text-neutral-500">
                      {priceBreakdown.isEnterprise
                        ? "We'll connect you with an enterprise specialist"
                        : "Get a personalized consultation before committing"}
                    </p>
                  </>
                )}
              </div>
              <button
                onClick={() => setShowModal(false)}
                className="p-1 hover:bg-neutral-100 rounded-lg transition-colors"
              >
                <X className="w-5 h-5 text-neutral-500" />
              </button>
            </div>

            {/* Walkthrough Success State */}
            {modalMode === 'walkthrough' && walkthroughSubmitted ? (
              <div className="p-6 text-center">
                <div className="w-16 h-16 rounded-full bg-brand-green-100 flex items-center justify-center mx-auto mb-4">
                  <CheckCircle2 className="w-8 h-8 text-brand-green-600" />
                </div>
                <h4 className="text-lg font-bold text-neutral-900 mb-2">Request Submitted!</h4>
                <p className="text-neutral-600 mb-4">
                  Our team will reach out within 24 hours to schedule your walkthrough.
                </p>
                <p className="text-sm text-neutral-500 mb-6">
                  Check your email ({contactForm.email}) for confirmation.
                </p>
                <div className="flex flex-col gap-3">
                  <button
                    onClick={() => {
                      setShowModal(false)
                      openCheckoutModal()
                    }}
                    className="w-full bg-gradient-to-r from-brand-gold-500 to-amber-500 text-white font-bold py-3 rounded-xl hover:from-brand-gold-400 hover:to-amber-400 transition-all"
                  >
                    Proceed to Checkout Anyway
                  </button>
                  <button
                    onClick={() => setShowModal(false)}
                    className="w-full py-3 text-neutral-600 font-medium hover:text-neutral-900"
                  >
                    I'll Wait for the Call
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div className="p-4 space-y-4">
                  {/* Package Summary */}
                  <div className={`rounded-lg p-4 border ${
                    modalMode === 'checkout'
                      ? 'bg-brand-gold-50 border-brand-gold-200'
                      : 'bg-brand-sky-50 border-brand-sky-200'
                  }`}>
                    <div className="flex justify-between items-start mb-2">
                      <div>
                        <span className={`text-sm font-medium ${
                          modalMode === 'checkout' ? 'text-brand-gold-800' : 'text-brand-sky-800'
                        }`}>
                          {config.blockCount} block{config.blockCount !== 1 ? 's' : ''}
                          {config.isRecurring && ` (${config.recurringFrequency})`}
                        </span>
                        {priceBreakdown.isEnterprise && (
                          <span className="ml-2 text-xs bg-brand-brown-100 text-brand-brown-700 px-2 py-0.5 rounded-full">
                            Enterprise
                          </span>
                        )}
                      </div>
                      <span className={`text-xl font-bold ${
                        modalMode === 'checkout' ? 'text-brand-gold-800' : 'text-brand-sky-800'
                      }`}>
                        {formatPrice(priceBreakdown.total)}
                      </span>
                    </div>
                    <div className="text-xs text-brand-green-600 flex items-center gap-1">
                      <CheckCircle2 className="w-3 h-3" />
                      {formatPrice(priceBreakdown.workerPayoutTotal)} goes directly to workers
                    </div>
                  </div>

                  {/* Walkthrough Benefits */}
                  {modalMode === 'walkthrough' && (
                    <div className="bg-neutral-50 rounded-lg p-4 space-y-2">
                      <p className="text-sm font-medium text-neutral-700">What you'll get:</p>
                      <ul className="text-sm text-neutral-600 space-y-1">
                        <li className="flex items-start gap-2">
                          <CheckCircle2 className="w-4 h-4 text-brand-green-500 flex-shrink-0 mt-0.5" />
                          <span>15-30 min call with our sponsorship team</span>
                        </li>
                        <li className="flex items-start gap-2">
                          <CheckCircle2 className="w-4 h-4 text-brand-green-500 flex-shrink-0 mt-0.5" />
                          <span>Custom package recommendations based on your goals</span>
                        </li>
                        <li className="flex items-start gap-2">
                          <CheckCircle2 className="w-4 h-4 text-brand-green-500 flex-shrink-0 mt-0.5" />
                          <span>White-label branding and media opportunities</span>
                        </li>
                        {priceBreakdown.isEnterprise && (
                          <li className="flex items-start gap-2">
                            <CheckCircle2 className="w-4 h-4 text-brand-green-500 flex-shrink-0 mt-0.5" />
                            <span>Dedicated account manager introduction</span>
                          </li>
                        )}
                      </ul>
                    </div>
                  )}

                  {/* Error Message */}
                  {checkoutError && (
                    <div className="bg-red-50 text-red-700 px-3 py-2 rounded-lg text-sm">
                      {checkoutError}
                    </div>
                  )}

                  {/* Contact Form */}
                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Name <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={contactForm.name}
                      onChange={(e) => setContactForm({ ...contactForm, name: e.target.value })}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-brand-gold-500 focus:border-brand-gold-500"
                      placeholder="Your full name"
                    />
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Email <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="email"
                      value={contactForm.email}
                      onChange={(e) => setContactForm({ ...contactForm, email: e.target.value })}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-brand-gold-500 focus:border-brand-gold-500"
                      placeholder="your@email.com"
                    />
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Phone {modalMode === 'walkthrough' ? <span className="text-red-500">*</span> : <span className="text-neutral-400">(optional)</span>}
                    </label>
                    <input
                      type="tel"
                      value={contactForm.phone}
                      onChange={(e) => setContactForm({ ...contactForm, phone: e.target.value })}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-brand-gold-500 focus:border-brand-gold-500"
                      placeholder="(555) 123-4567"
                    />
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Company / Organization <span className="text-neutral-400">(optional)</span>
                    </label>
                    <input
                      type="text"
                      value={contactForm.company}
                      onChange={(e) => setContactForm({ ...contactForm, company: e.target.value })}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-brand-gold-500 focus:border-brand-gold-500"
                      placeholder="Company name"
                    />
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      {modalMode === 'checkout' ? 'Notes' : 'Questions or Goals'} <span className="text-neutral-400">(optional)</span>
                    </label>
                    <textarea
                      value={contactForm.notes}
                      onChange={(e) => setContactForm({ ...contactForm, notes: e.target.value })}
                      rows={2}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-brand-gold-500 focus:border-brand-gold-500 resize-none"
                      placeholder={
                        modalMode === 'checkout'
                          ? 'Any special requests or scheduling preferences...'
                          : 'What are you hoping to achieve with this sponsorship?'
                      }
                    />
                  </div>
                </div>

                {/* Modal Footer */}
                <div className="p-4 border-t bg-neutral-50 space-y-3">
                  {modalMode === 'checkout' ? (
                    <>
                      <button
                        onClick={handleCheckout}
                        disabled={isProcessing}
                        className="w-full bg-gradient-to-r from-brand-gold-500 to-amber-500 text-white font-bold py-4 rounded-xl flex items-center justify-center gap-2 hover:from-brand-gold-400 hover:to-amber-400 transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
                      >
                        {isProcessing ? (
                          <>
                            <Loader2 className="w-5 h-5 animate-spin" />
                            Processing...
                          </>
                        ) : (
                          <>
                            <Lock className="w-5 h-5" />
                            Pay {formatPrice(priceBreakdown.total)} Securely
                          </>
                        )}
                      </button>

                      <div className="flex items-center justify-center gap-2 text-xs text-neutral-500">
                        <Shield className="w-3 h-3" />
                        <span>Your payment is secured by Stripe</span>
                      </div>

                      <p className="text-xs text-center text-neutral-400">
                        By proceeding, you agree to our terms. GCC is a 501(c)(3) nonprofit.
                        Your contribution is tax-deductible.
                      </p>
                    </>
                  ) : (
                    <>
                      <button
                        onClick={handleWalkthroughRequest}
                        disabled={isProcessing}
                        className="w-full bg-gradient-to-r from-brand-sky-500 to-blue-500 text-white font-bold py-4 rounded-xl flex items-center justify-center gap-2 hover:from-brand-sky-400 hover:to-blue-400 transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
                      >
                        {isProcessing ? (
                          <>
                            <Loader2 className="w-5 h-5 animate-spin" />
                            Submitting...
                          </>
                        ) : (
                          <>
                            <Calendar className="w-5 h-5" />
                            Request Walkthrough Call
                          </>
                        )}
                      </button>

                      <button
                        onClick={() => {
                          setModalMode('checkout')
                          setCheckoutError(null)
                        }}
                        className="w-full py-2 text-brand-gold-600 font-medium hover:text-brand-gold-700 flex items-center justify-center gap-2"
                      >
                        <CreditCard className="w-4 h-4" />
                        Skip to Checkout Instead
                      </button>

                      <p className="text-xs text-center text-neutral-400">
                        No commitment required. We'll reach out within 24 hours.
                      </p>
                    </>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
