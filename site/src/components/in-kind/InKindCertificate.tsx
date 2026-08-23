// Decorative in-kind donation certificate — landscape Letter, parallels the
// existing /sponsor/certificates/[id] certificate of appreciation.
// No dollar value shown; describes the contribution in narrative form.

import type { InKindDonation, InKindDonationItem } from '@/db/schema'
import { formatInKindGiftId, formatLongDate } from '@/lib/in-kind-donations'

export function InKindCertificate({
  donation,
  items,
}: {
  donation: InKindDonation
  items: InKindDonationItem[]
}) {
  const giftId = formatInKindGiftId(donation.id, donation.donationDate)
  const donor = donation.donorName
  const date = formatLongDate(donation.donationDate)
  const itemSummary = describeItems(items)

  return (
    <div
      className="aspect-[11/8.5] w-full bg-white p-12 shadow-2xl print:aspect-auto print:shadow-none"
      style={{
        backgroundImage:
          'radial-gradient(circle at top right, rgba(22,118,58,0.06), transparent 60%), radial-gradient(circle at bottom left, rgba(245,158,11,0.05), transparent 60%)',
      }}
    >
      <div className="flex h-full flex-col items-center justify-between border-[6px] border-double border-emerald-800/70 px-12 py-8 text-center">
        <div className="flex flex-col items-center">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src="/images/gcc-logo.png"
            alt="Global Community Cleaners NFP"
            className="h-40 w-auto object-contain"
          />
          <h1 className="mt-4 font-serif text-5xl font-bold tracking-tight text-neutral-900">
            Certificate of Appreciation
          </h1>
          <p className="mt-3 text-sm uppercase tracking-widest text-neutral-500">
            In Recognition of an In-Kind Contribution to
          </p>
          <p className="mt-1 text-sm uppercase tracking-widest text-emerald-800">
            Global Community Cleaners
          </p>
        </div>

        <div className="my-4">
          <p className="font-serif text-5xl italic text-emerald-900">{donor}</p>
          <div className="mx-auto mt-5 h-px w-2/3 bg-neutral-300" />
          <p className="mt-5 max-w-3xl text-balance text-base leading-relaxed text-neutral-700">
            In grateful recognition of a generous in-kind donation of {itemSummary} — materials that
            directly equip our cleanup crews working in North Lawndale and beyond. Your partnership
            supports the residents who clean their own blocks and keeps every dollar moving on the
            street and in the community.
          </p>
        </div>

        <div className="flex w-full items-end justify-between">
          <div className="text-left">
            <p className="border-t border-neutral-400 pt-2 font-serif text-xs uppercase tracking-widest text-neutral-500">
              Date of Gift
            </p>
            <p className="mt-1 text-sm text-neutral-800">{date}</p>
          </div>
          <div className="text-center">
            <p className="font-serif text-xl italic text-emerald-900">
              Global Community Cleaners NFP
            </p>
            <p className="text-[10px] uppercase tracking-widest text-neutral-500">501(c)(3)</p>
          </div>
          <div className="text-right">
            <p className="border-t border-neutral-400 pt-2 font-serif text-xs uppercase tracking-widest text-neutral-500">
              Gift ID
            </p>
            <p className="mt-1 text-sm text-neutral-800">{giftId}</p>
          </div>
        </div>
      </div>
    </div>
  )
}

// Compact human-readable item summary for the certificate body.
function describeItems(items: InKindDonationItem[]): string {
  if (items.length === 0) return 'donated materials'
  const sorted = [...items].sort((a, b) => a.sortOrder - b.sortOrder)
  const parts = sorted.map((it) => {
    const unit = it.unitLabel ? ` ${it.unitLabel} of` : ''
    return `${it.quantity}${unit} ${it.description.toLowerCase()}`
  })
  if (parts.length === 1) return parts[0]
  if (parts.length === 2) return parts.join(' and ')
  return parts.slice(0, -1).join(', ') + ', and ' + parts[parts.length - 1]
}
