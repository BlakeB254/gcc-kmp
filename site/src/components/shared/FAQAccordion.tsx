'use client'

import { useState } from 'react'

export interface FAQItem {
  question: string
  answer: string
}

interface FAQAccordionProps {
  /** FAQ items to display */
  items: FAQItem[]
  /** Maximum number of items to show (undefined = show all) */
  limit?: number
}

function AccordionItem({
  item,
  isOpen,
  onToggle,
  index,
}: {
  item: FAQItem
  isOpen: boolean
  onToggle: () => void
  index: number
}) {
  return (
    <div
      className="border-b border-neutral-200 last:border-b-0"
      style={{ animationDelay: `${index * 0.1}s` }}
    >
      <button
        onClick={onToggle}
        className="w-full py-golden-md px-golden-sm flex items-center justify-between text-left group focus-glow rounded-lg"
        aria-expanded={isOpen}
      >
        <span className="text-base sm:text-lg font-medium text-neutral-900 pr-4 group-hover:text-brand-green-700 transition-colors">
          {item.question}
        </span>
        <span
          className={`
            flex-shrink-0 w-8 h-8 rounded-full bg-brand-green-100
            flex items-center justify-center transition-all duration-300
            group-hover:bg-brand-green-200
            ${isOpen ? 'rotate-180 bg-brand-green-500' : ''}
          `}
        >
          <svg
            className={`w-5 h-5 transition-colors ${isOpen ? 'text-white' : 'text-brand-green-600'}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
          </svg>
        </span>
      </button>
      <div
        className={`
          overflow-hidden transition-all duration-300 ease-out
          ${isOpen ? 'max-h-96 opacity-100' : 'max-h-0 opacity-0'}
        `}
      >
        <p className="px-golden-sm pb-golden-md text-neutral-600 leading-relaxed animate-fade-in-up">
          {item.answer}
        </p>
      </div>
    </div>
  )
}

export function FAQAccordion({ items, limit }: FAQAccordionProps) {
  const [openIndex, setOpenIndex] = useState<number | null>(0)

  const displayItems = limit ? items.slice(0, limit) : items

  return (
    <div className="bg-neutral-50 rounded-2xl p-golden-md sm:p-golden-lg border border-neutral-100 hover-lift">
      {displayItems.map((item, index) => (
        <AccordionItem
          key={index}
          item={item}
          index={index}
          isOpen={openIndex === index}
          onToggle={() => setOpenIndex(openIndex === index ? null : index)}
        />
      ))}
    </div>
  )
}
