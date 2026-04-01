'use client'

import Link from 'next/link'
import { Gift, Star, MapPin, Shield, Sparkles, X } from 'lucide-react'

const UPSELL_ITEMS = [
  { icon: Star, title: 'Custom Branding', desc: 'Your logo on the block marker, worker vest, and impact report' },
  { icon: MapPin, title: 'Dedicated Route', desc: 'A named route in your honor with permanent map presence' },
  { icon: Shield, title: 'Sponsor Profile Page', desc: 'Public profile showcasing your community impact' },
] as const

interface UpsellModalProps {
  onClose: () => void
  onProceed: () => void
  calculatorUrl: string
}

export function UpsellModal({ onClose, onProceed, calculatorUrl }: UpsellModalProps) {
  return (
    <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4" onClick={onClose} role="presentation">
      <div
        className="bg-white rounded-2xl max-w-lg w-full shadow-2xl overflow-hidden animate-scale-in"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="upsell-modal-title"
      >
        <div className="bg-gradient-to-r from-brand-green-500 to-amber-500 p-6 text-white relative">
          <button
            onClick={onClose}
            className="absolute top-4 right-4 text-white/80 hover:text-white transition-colors"
            aria-label="Close upsell dialog"
          >
            <X className="w-6 h-6" />
          </button>
          <div className="flex items-center gap-3 mb-2">
            <Gift className="w-7 h-7" />
            <h3 id="upsell-modal-title" className="text-xl font-bold">Before you checkout...</h3>
          </div>
          <p className="text-white/90 text-sm">
            Did you know you can maximize your impact with add-ons?
          </p>
        </div>
        <div className="p-6 space-y-4">
          <div className="space-y-3">
            {UPSELL_ITEMS.map(({ icon: Icon, title, desc }) => (
              <div key={title} className="flex items-start gap-3 p-3 rounded-lg bg-neutral-50">
                <Icon className="w-5 h-5 text-brand-green-600 flex-shrink-0 mt-0.5" />
                <div>
                  <p className="font-medium text-neutral-900 text-sm">{title}</p>
                  <p className="text-xs text-neutral-500">{desc}</p>
                </div>
              </div>
            ))}
          </div>
          <div className="flex flex-col gap-2 pt-2">
            <Link
              href={calculatorUrl}
              className="w-full py-3 rounded-xl bg-brand-green-500 hover:bg-brand-green-600 text-brand-green-900 font-bold text-center transition-colors flex items-center justify-center gap-2"
            >
              <Sparkles className="w-4 h-4" />
              Explore Custom Packages
            </Link>
            <button
              onClick={() => { onClose(); onProceed() }}
              className="w-full py-3 rounded-xl border border-neutral-200 text-neutral-700 font-medium text-center hover:bg-neutral-50 transition-colors"
            >
              No thanks, proceed to checkout →
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
