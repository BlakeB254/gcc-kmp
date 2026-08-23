// Donor-facing in-kind donation acknowledgment letter.
// Follows IRS Pub 1771: describes items but does NOT state a dollar value.
// The donor determines fair-market-value for their tax deduction.

import type { InKindDonation, InKindDonationItem } from '@/db/schema'
import {
  IRS_NO_GOODS_STATEMENT,
  ORG_NAME,
  ORG_TAGLINE,
  formatInKindGiftId,
  formatLongDate,
  defaultThankYouMessage,
} from '@/lib/in-kind-donations'

const ORG_ADDRESS = process.env.GCC_MAILING_ADDRESS || 'Chicago, IL'
const ORG_EIN = process.env.GCC_EIN || '00-0000000'

export function InKindLetter({
  donation,
  items,
}: {
  donation: InKindDonation
  items: InKindDonationItem[]
}) {
  const giftId = formatInKindGiftId(donation.id, donation.donationDate)
  const donorDisplay = donation.donorName
  const sortedItems = [...items].sort((a, b) => a.sortOrder - b.sortOrder)
  const thankYou = donation.thankYouMessage || defaultThankYouMessage(donorDisplay)
  const addressLines = [
    donation.donorAddressStreet,
    [donation.donorAddressCity, donation.donorAddressState, donation.donorAddressZip]
      .filter(Boolean)
      .join(donation.donorAddressState ? ', ' : ' '),
  ].filter(Boolean)

  return (
    <div className="bg-white p-12 shadow-lg print:p-4 print:shadow-none">
      {/* Header — logo top right, org/gift info on the left. */}
      <div className="flex items-start justify-between gap-6 border-b border-neutral-200 pb-6 print:pb-4">
        <div className="flex flex-col">
          <p className="text-xs font-semibold uppercase tracking-widest text-emerald-700">
            In-Kind Donation Acknowledgment
          </p>
          <p className="mt-3 text-base font-semibold text-neutral-900">{ORG_NAME}</p>
          <p className="mt-1 text-sm text-neutral-600">{ORG_ADDRESS}</p>
          <p className="mt-1 text-sm text-neutral-600">EIN: {ORG_EIN}</p>
          <p className="mt-1 text-sm text-neutral-600">{ORG_TAGLINE}</p>
          <div className="mt-5 flex flex-wrap gap-x-8 gap-y-2 text-sm">
            <div>
              <p className="text-xs uppercase tracking-widest text-neutral-500">Gift ID</p>
              <p className="mt-1 font-semibold text-neutral-900">{giftId}</p>
            </div>
            <div>
              <p className="text-xs uppercase tracking-widest text-neutral-500">Donation Date</p>
              <p className="mt-1 text-neutral-900">{formatLongDate(donation.donationDate)}</p>
            </div>
          </div>
        </div>
        <div className="shrink-0">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/images/gcc-logo.png" alt={ORG_NAME} className="h-40 w-auto object-contain print:h-28" />
        </div>
      </div>

      {/* Donor block */}
      <div className="mt-8 print:mt-5">
        <p className="text-xs uppercase tracking-widest text-neutral-500">Donor</p>
        <p className="mt-2 text-lg font-semibold text-neutral-900">{donorDisplay}</p>
        {donation.donorContactName && (
          <p className="mt-1 text-sm text-neutral-700">Attn: {donation.donorContactName}</p>
        )}
        {addressLines.map((line) => (
          <p key={line} className="mt-1 text-sm text-neutral-700">
            {line}
          </p>
        ))}
        {donation.donorEmail && (
          <p className="mt-1 text-sm text-neutral-700">{donation.donorEmail}</p>
        )}
      </div>

      {/* Items table */}
      <div className="mt-8 rounded-lg border border-emerald-200 bg-emerald-50/40 p-6 print:mt-5 print:p-4">
        <p className="text-xs uppercase tracking-widest text-emerald-800">Donated Items</p>
        <table className="mt-4 w-full text-sm">
          <thead>
            <tr className="border-b border-emerald-200/70 text-left text-xs uppercase tracking-wider text-emerald-900/70">
              <th className="py-2 font-medium">Qty</th>
              <th className="py-2 font-medium">Unit</th>
              <th className="py-2 font-medium">Description</th>
              <th className="py-2 text-right font-medium">Condition</th>
            </tr>
          </thead>
          <tbody>
            {sortedItems.map((item) => (
              <tr key={item.id} className="border-b border-emerald-100 last:border-0">
                <td className="py-2.5 align-top font-semibold text-emerald-900">{item.quantity}</td>
                <td className="py-2.5 align-top text-neutral-700">{item.unitLabel || '—'}</td>
                <td className="py-2.5 align-top text-neutral-800">{item.description}</td>
                <td className="py-2.5 text-right align-top text-neutral-600">{item.condition || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {donation.dropOffLocation && (
          <p className="mt-4 text-xs text-emerald-900/80">
            Received at: <span className="font-medium">{donation.dropOffLocation}</span>
          </p>
        )}
      </div>

      {/* Narrative */}
      <div className="mt-8 space-y-3 text-sm leading-relaxed text-neutral-700 print:mt-5 print:space-y-2 print:text-[13px] print:leading-snug">
        <p>{thankYou}</p>
        <p>
          <strong>{IRS_NO_GOODS_STATEMENT}</strong> The donor is responsible for determining fair
          market value for tax-deduction purposes; gifts valued over $5,000 may require a qualified
          appraisal (IRS Form 8283, Section B). Please retain this acknowledgment for your tax
          records.
        </p>
      </div>

      {/* Sign-off block — signature is optional. When no signing officer is
          set, render just the org/date stamp full-width since IRS Pub 1771
          does not require a signature on charitable acknowledgments. */}
      {donation.signingOfficerName ? (
        <div className="mt-12 grid grid-cols-2 gap-8">
          <div>
            <p className="border-t border-neutral-400 pt-2 font-serif text-xs uppercase tracking-widest text-neutral-500">
              Authorized signature
            </p>
            <p className="mt-1 text-sm font-semibold text-neutral-900">{donation.signingOfficerName}</p>
            {donation.signingOfficerTitle && (
              <p className="text-xs text-neutral-600">{donation.signingOfficerTitle}</p>
            )}
          </div>
          <div className="text-right">
            <p className="border-t border-neutral-400 pt-2 font-serif text-xs uppercase tracking-widest text-neutral-500">
              Date acknowledged
            </p>
            <p className="mt-1 text-sm text-neutral-900">
              {donation.acknowledgedAt
                ? formatLongDate(donation.acknowledgedAt.slice(0, 10))
                : formatLongDate(new Date().toISOString().slice(0, 10))}
            </p>
          </div>
        </div>
      ) : (
        <div className="mt-12 flex items-end justify-between border-t border-neutral-200 pt-4 print:mt-3 print:pt-2">
          <div>
            <p className="font-serif text-lg italic text-emerald-900">{ORG_NAME}</p>
            <p className="text-[10px] uppercase tracking-widest text-neutral-500">{ORG_TAGLINE}</p>
          </div>
          <div className="text-right">
            <p className="text-xs uppercase tracking-widest text-neutral-500">Date acknowledged</p>
            <p className="mt-1 text-sm text-neutral-900">
              {donation.acknowledgedAt
                ? formatLongDate(donation.acknowledgedAt.slice(0, 10))
                : formatLongDate(new Date().toISOString().slice(0, 10))}
            </p>
          </div>
        </div>
      )}

    </div>
  )
}
