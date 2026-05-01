import { notFound } from 'next/navigation'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { sponsors, transactions } from '@/db/schema'
import { formatDollars } from '@/lib/sponsor-deliverables'
import { PrintButton } from '@/components/sponsor/PrintButton'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const ORG_NAME = 'Global Community Cleaners NFP'
const ORG_ADDRESS = process.env.GCC_MAILING_ADDRESS || 'Chicago, IL'
const ORG_EIN = process.env.GCC_EIN || '00-0000000'

// Receipts keyed by transaction id, available for any completed donation
// regardless of amount — for sponsors below the $2K deliverable threshold.

export default async function TransactionReceiptPage({
  params,
}: {
  params: Promise<{ txId: string }>
}) {
  const { txId: idRaw } = await params
  const id = Number(idRaw)
  if (!Number.isFinite(id)) notFound()

  const [tx] = await db
    .select()
    .from(transactions)
    .where(eq(transactions.id, id))
    .limit(1)
  if (!tx || tx.status !== 'completed') notFound()

  const [sponsor] = await db
    .select()
    .from(sponsors)
    .where(eq(sponsors.id, tx.sponsorId!))
    .limit(1)
  if (!sponsor) notFound()

  const display = sponsor.businessName || sponsor.name
  const date = new Date(tx.createdAt).toLocaleDateString('en-US', { dateStyle: 'long' })
  const amountCents = Number(tx.amount)
  const amount = formatDollars(amountCents)
  const year = new Date(tx.createdAt).getUTCFullYear()
  const receiptNumber = `GCC-${year}-TX-${String(tx.id).padStart(5, '0')}`

  return (
    <div className="min-h-screen bg-neutral-100 py-12 print:bg-white print:py-0">
      <div className="mx-auto max-w-3xl px-4 print:px-0">
        <div className="mb-6 flex items-center justify-between print:hidden">
          <a href="/sponsor/wall" className="text-sm text-neutral-600 hover:text-neutral-900">
            ← Sponsor wall
          </a>
          <PrintButton />
        </div>

        <div className="bg-white p-12 shadow-lg print:shadow-none">
          <div className="flex items-start justify-between gap-6 border-b border-neutral-200 pb-6">
            <div className="flex flex-col">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src="/images/gcc-logo.png"
                alt={ORG_NAME}
                className="h-20 w-auto object-contain"
              />
              <p className="mt-3 text-xs font-semibold uppercase tracking-widest text-emerald-700">
                Donation Receipt
              </p>
              <p className="mt-2 text-sm text-neutral-600">{ORG_ADDRESS}</p>
              <p className="mt-1 text-sm text-neutral-600">EIN: {ORG_EIN}</p>
              <p className="mt-1 text-sm text-neutral-600">501(c)(3) tax-exempt nonprofit</p>
            </div>
            <div className="text-right">
              <p className="text-xs uppercase tracking-widest text-neutral-500">Receipt #</p>
              <p className="mt-1 text-lg font-semibold text-neutral-900">{receiptNumber}</p>
              <p className="mt-3 text-xs uppercase tracking-widest text-neutral-500">Date</p>
              <p className="mt-1 text-sm text-neutral-900">{date}</p>
            </div>
          </div>

          <div className="mt-8">
            <p className="text-xs uppercase tracking-widest text-neutral-500">Donor</p>
            <p className="mt-2 text-lg font-semibold text-neutral-900">{display}</p>
            {sponsor.addressStreet && (
              <p className="mt-1 text-sm text-neutral-700">
                {sponsor.addressStreet}
                {sponsor.addressCity ? `, ${sponsor.addressCity}` : ''}
                {sponsor.addressState ? `, ${sponsor.addressState}` : ''}
                {sponsor.addressZip ? ` ${sponsor.addressZip}` : ''}
              </p>
            )}
            <p className="mt-1 text-sm text-neutral-700">{sponsor.email}</p>
          </div>

          <div className="mt-8 rounded-lg bg-emerald-50 p-6">
            <p className="text-xs uppercase tracking-widest text-emerald-800">Contribution</p>
            <p className="mt-2 text-4xl font-bold text-emerald-900">{amount}</p>
            <p className="mt-2 text-sm text-emerald-900/80">
              Source: Online (Stripe)
              {tx.allocationType === 'block' ? ' · Block sponsorship' : ''}
              {tx.allocationType === 'general' ? ' · General donation' : ''}
            </p>
          </div>

          <div className="mt-8 space-y-3 text-sm leading-relaxed text-neutral-700">
            <p>
              Thank you for your generous contribution to {ORG_NAME}, a registered 501(c)(3)
              tax-exempt nonprofit organization.
            </p>
            <p>
              <strong>No goods or services were provided</strong> in exchange for this contribution.
              Your gift is tax-deductible to the fullest extent allowed by law.
            </p>
            <p>
              Please retain this receipt for your records. Consult your tax advisor for guidance
              on charitable deduction limits applicable to your situation.
            </p>
          </div>

          <div className="mt-12 border-t border-neutral-200 pt-6 text-center text-xs text-neutral-500">
            <p>This receipt was generated electronically and is valid without a signature.</p>
          </div>
        </div>
      </div>

      <style>{`
        @media print {
          @page { size: letter; margin: 0.5in; }
        }
      `}</style>
    </div>
  )
}
