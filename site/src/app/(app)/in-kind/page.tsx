import Link from 'next/link'
import { listInKindBundles } from '@/lib/in-kind-loader'
import { formatDollars, formatInKindGiftId, formatLongDate } from '@/lib/in-kind-donations'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export default async function InKindIndexPage() {
  const bundles = await listInKindBundles()
  const real = bundles.filter((b) => !b.donation.isTemplate)
  const templates = bundles.filter((b) => b.donation.isTemplate)
  const fallback = bundles.some((b) => b.source === 'seed-fallback')

  return (
    <div className="mx-auto max-w-4xl px-6 py-12">
      <div className="mb-8 flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold text-neutral-900">In-Kind Donations</h1>
          <p className="mt-1 text-sm text-neutral-600">
            Acknowledgment records for received gifts of goods, equipment, or services.
          </p>
        </div>
      </div>

      {fallback && (
        <div className="mb-6 rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900">
          <strong>Seed fallback in use.</strong> No rows found in the database — rendering from the
          typed seed registry. Run <code className="rounded bg-amber-100 px-1 py-0.5">npm run seed:in-kind</code>{' '}
          to persist these records.
        </div>
      )}

      <h2 className="mb-3 text-xs font-semibold uppercase tracking-widest text-emerald-700">
        Received gifts
      </h2>
      {real.length === 0 ? (
        <p className="rounded-md border border-neutral-200 bg-neutral-50 p-4 text-sm text-neutral-600">
          No in-kind gifts recorded yet.
        </p>
      ) : (
        <ul className="space-y-3">
          {real.map((b) => (
            <DonationRow key={b.donation.slug} bundle={b} />
          ))}
        </ul>
      )}

      <h2 className="mb-3 mt-10 text-xs font-semibold uppercase tracking-widest text-neutral-500">
        Templates
      </h2>
      <ul className="space-y-3">
        {templates.map((b) => (
          <DonationRow key={b.donation.slug} bundle={b} />
        ))}
      </ul>

      <div className="mt-12 rounded-lg border border-neutral-200 bg-neutral-50 p-6 text-sm leading-relaxed text-neutral-700">
        <p className="font-semibold text-neutral-900">Each gift produces three documents:</p>
        <ul className="mt-3 list-disc space-y-1 pl-5">
          <li>
            <strong>Donor letter</strong> — IRS-compliant acknowledgment (no $ value shown).
          </li>
          <li>
            <strong>Certificate</strong> — landscape decorative certificate for framing or social share.
          </li>
          <li>
            <strong>Internal record</strong> — staff-facing with FMV, value source, stewardship metadata.
          </li>
        </ul>
        <p className="mt-3 text-xs text-neutral-500">
          Print any document to PDF (Ctrl/Cmd-P) — print stylesheets are tuned for letter-size output.
        </p>
      </div>
    </div>
  )
}

function DonationRow({ bundle }: { bundle: Awaited<ReturnType<typeof listInKindBundles>>[number] }) {
  const { donation } = bundle
  const giftId = formatInKindGiftId(donation.id, donation.donationDate)
  return (
    <li className="rounded-lg border border-neutral-200 bg-white p-5 shadow-sm">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <p className="text-base font-semibold text-neutral-900">{donation.donorName}</p>
          <p className="text-xs text-neutral-500">
            {giftId} · {formatLongDate(donation.donationDate)}
          </p>
        </div>
        <div className="text-right">
          <span
            className={`inline-block rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-widest ${
              donation.status === 'acknowledged'
                ? 'bg-emerald-100 text-emerald-800'
                : donation.isTemplate
                ? 'bg-neutral-200 text-neutral-700'
                : 'bg-amber-100 text-amber-800'
            }`}
          >
            {donation.isTemplate ? 'template' : donation.status}
          </span>
          <p className="mt-1 text-xs text-neutral-500">
            Est. value: {formatDollars(donation.estimatedValueCents)}
          </p>
        </div>
      </div>
      <div className="mt-4 flex flex-wrap gap-2 text-xs">
        <Link
          href={`/in-kind/${donation.slug}/letter`}
          className="rounded-md bg-emerald-700 px-3 py-1.5 font-medium text-white hover:bg-emerald-800"
        >
          Donor letter
        </Link>
        <Link
          href={`/in-kind/${donation.slug}/certificate`}
          className="rounded-md border border-emerald-700 px-3 py-1.5 font-medium text-emerald-800 hover:bg-emerald-50"
        >
          Certificate
        </Link>
        <Link
          href={`/in-kind/${donation.slug}/internal`}
          className="rounded-md border border-neutral-400 px-3 py-1.5 font-medium text-neutral-700 hover:bg-neutral-100"
        >
          Internal record
        </Link>
      </div>
    </li>
  )
}
