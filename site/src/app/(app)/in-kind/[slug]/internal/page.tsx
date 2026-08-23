import { notFound } from 'next/navigation'
import { PrintButton } from '@/components/sponsor/PrintButton'
import { InKindInternalRecord } from '@/components/in-kind/InKindInternalRecord'
import { getInKindBundleBySlug } from '@/lib/in-kind-loader'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export default async function InKindInternalPage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  const bundle = await getInKindBundleBySlug(slug)
  if (!bundle) notFound()

  return (
    <div className="min-h-screen bg-neutral-100 py-12 print:bg-white print:py-0">
      <div className="mx-auto max-w-3xl px-4 print:px-0">
        <div className="mb-6 flex items-center justify-between print:hidden">
          <a href="/in-kind" className="text-sm text-neutral-600 hover:text-neutral-900">
            ← All in-kind donations
          </a>
          <PrintButton label="Print internal record" />
        </div>

        <div className="mb-4 rounded-md border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900 print:hidden">
          <strong>Staff-facing record.</strong> Contains FMV and internal notes — do not send to donor.
        </div>

        <InKindInternalRecord donation={bundle.donation} items={bundle.items} />
      </div>

      <style>{`
        @media print {
          @page { size: letter; margin: 0.5in; }
        }
      `}</style>
    </div>
  )
}
