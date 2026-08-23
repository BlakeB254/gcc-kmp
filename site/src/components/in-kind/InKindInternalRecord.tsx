// Internal gift record — staff-facing. Shows everything: FMV, value source,
// storage location, stewardship metadata, signing officer, notes. Used for
// grant reporting, Form 990 Schedule M, and audit trail. Never shared with the
// donor — values and internal notes are not part of the donor-facing letter.

import type { InKindDonation, InKindDonationItem } from '@/db/schema'
import {
  ORG_NAME,
  formatDollars,
  formatInKindGiftId,
  formatLongDate,
  requiresAppraisal,
  totalEstimatedValueCents,
} from '@/lib/in-kind-donations'

export function InKindInternalRecord({
  donation,
  items,
}: {
  donation: InKindDonation
  items: InKindDonationItem[]
}) {
  const giftId = formatInKindGiftId(donation.id, donation.donationDate)
  const sortedItems = [...items].sort((a, b) => a.sortOrder - b.sortOrder)
  const totalCents = totalEstimatedValueCents(sortedItems, donation.estimatedValueCents)
  const appraisalNeeded = requiresAppraisal(totalCents)

  return (
    <div className="bg-white p-12 shadow-lg print:shadow-none">
      <div className="border-b border-neutral-200 pb-6">
        <div className="flex items-start justify-between gap-6">
          <div>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/images/gcc-logo.png" alt={ORG_NAME} className="h-14 w-auto object-contain" />
            <p className="mt-3 text-xs font-semibold uppercase tracking-widest text-amber-700">
              Internal Gift Record · Not for Donor
            </p>
            <p className="mt-2 text-sm text-neutral-600">{ORG_NAME}</p>
          </div>
          <div className="text-right">
            <p className="text-xs uppercase tracking-widest text-neutral-500">Gift ID</p>
            <p className="mt-1 text-lg font-semibold text-neutral-900">{giftId}</p>
            <span
              className={`mt-3 inline-block rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-widest ${
                donation.status === 'acknowledged'
                  ? 'bg-emerald-100 text-emerald-800'
                  : donation.status === 'archived'
                  ? 'bg-neutral-200 text-neutral-700'
                  : 'bg-amber-100 text-amber-800'
              }`}
            >
              {donation.status}
            </span>
          </div>
        </div>
      </div>

      {/* Donor */}
      <Section title="Donor">
        <Row label="Name" value={donation.donorName} />
        <Row label="Type" value={donation.donorType} />
        <Row label="Contact" value={donation.donorContactName} />
        <Row label="Email" value={donation.donorEmail} />
        <Row label="Phone" value={donation.donorPhone} />
        <Row
          label="Address"
          value={[
            donation.donorAddressStreet,
            [donation.donorAddressCity, donation.donorAddressState, donation.donorAddressZip]
              .filter(Boolean)
              .join(' '),
          ]
            .filter(Boolean)
            .join(' · ')}
        />
      </Section>

      {/* Gift event */}
      <Section title="Gift Event">
        <Row label="Donation date" value={formatLongDate(donation.donationDate)} />
        <Row
          label="Received date"
          value={donation.receivedDate ? formatLongDate(donation.receivedDate) : undefined}
        />
        <Row label="Drop-off location" value={donation.dropOffLocation} />
        <Row label="Program / fund" value={donation.programFund} />
        <Row label="Storage location" value={donation.storageLocation} />
        <Row label="Intended use" value={donation.intendedUse} />
      </Section>

      {/* Items */}
      <Section title="Donated Items">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-neutral-200 text-left text-xs uppercase tracking-wider text-neutral-500">
              <th className="py-2 font-medium">Qty</th>
              <th className="py-2 font-medium">Unit</th>
              <th className="py-2 font-medium">Description</th>
              <th className="py-2 font-medium">Condition</th>
              <th className="py-2 text-right font-medium">Est. unit value</th>
              <th className="py-2 text-right font-medium">Est. line value</th>
            </tr>
          </thead>
          <tbody>
            {sortedItems.map((item) => {
              const line =
                item.estimatedUnitValueCents != null
                  ? item.estimatedUnitValueCents * item.quantity
                  : null
              return (
                <tr key={item.id} className="border-b border-neutral-100 last:border-0">
                  <td className="py-2 align-top text-neutral-900">{item.quantity}</td>
                  <td className="py-2 align-top text-neutral-700">{item.unitLabel || '—'}</td>
                  <td className="py-2 align-top text-neutral-800">{item.description}</td>
                  <td className="py-2 align-top text-neutral-600">{item.condition || '—'}</td>
                  <td className="py-2 text-right align-top text-neutral-700">
                    {formatDollars(item.estimatedUnitValueCents)}
                  </td>
                  <td className="py-2 text-right align-top text-neutral-900">{formatDollars(line)}</td>
                </tr>
              )
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-neutral-300 font-semibold">
              <td colSpan={5} className="py-3 text-right text-neutral-600">
                Headline estimated value (donor-provided / staff estimate):
              </td>
              <td className="py-3 text-right text-emerald-900">
                {formatDollars(donation.estimatedValueCents)}
              </td>
            </tr>
          </tfoot>
        </table>
      </Section>

      {/* Valuation */}
      <Section title="Valuation">
        <Row label="Headline estimate" value={formatDollars(donation.estimatedValueCents)} />
        <Row label="Total (line items)" value={formatDollars(totalCents)} />
        <Row label="Value source" value={donation.valueSource.replace(/_/g, ' ')} />
        <Row label="Value notes" value={donation.valueNotes} />
        {appraisalNeeded && (
          <div className="mt-3 rounded-md border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900">
            <strong>Form 8283 reminder:</strong> Estimated value ≥ $5,000. Donor must obtain a
            qualified appraisal and Section B of IRS Form 8283 must be signed by GCC as the
            recipient organization.
          </div>
        )}
      </Section>

      {/* Acknowledgment */}
      <Section title="Acknowledgment">
        <Row label="Status" value={donation.status} />
        <Row
          label="Acknowledged at"
          value={donation.acknowledgedAt ? formatLongDate(donation.acknowledgedAt.slice(0, 10)) : 'Not yet sent'}
        />
        <Row label="Method" value={donation.acknowledgmentMethod} />
        <Row label="Signing officer" value={donation.signingOfficerName} />
        <Row label="Officer title" value={donation.signingOfficerTitle} />
      </Section>

      {donation.notes && (
        <Section title="Internal Notes">
          <p className="whitespace-pre-wrap text-sm leading-relaxed text-neutral-700">{donation.notes}</p>
        </Section>
      )}

      <div className="mt-10 border-t border-neutral-200 pt-4 text-center text-xs text-neutral-500">
        <p>
          Internal record only — do not send to donor. Created{' '}
          {formatLongDate(donation.createdAt.slice(0, 10))}.
        </p>
      </div>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mt-8">
      <p className="text-xs font-semibold uppercase tracking-widest text-emerald-800">{title}</p>
      <div className="mt-3">{children}</div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: string | null | undefined }) {
  if (!value) return null
  return (
    <div className="grid grid-cols-[180px_1fr] gap-3 border-b border-neutral-100 py-1.5 text-sm last:border-0">
      <p className="text-neutral-500">{label}</p>
      <p className="text-neutral-900">{value}</p>
    </div>
  )
}
