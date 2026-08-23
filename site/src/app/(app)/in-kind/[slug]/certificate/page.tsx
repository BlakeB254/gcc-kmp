import { notFound } from 'next/navigation'
import { PrintButton } from '@/components/sponsor/PrintButton'
import { InKindCertificate } from '@/components/in-kind/InKindCertificate'
import { getInKindBundleBySlug } from '@/lib/in-kind-loader'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export default async function InKindCertificatePage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  const bundle = await getInKindBundleBySlug(slug)
  if (!bundle) notFound()

  return (
    <div className="min-h-screen bg-neutral-100 py-12 print:bg-white print:p-0">
      <div className="mx-auto max-w-[1100px] px-4 print:px-0">
        <div className="mb-6 flex items-center justify-between print:hidden">
          <a href="/in-kind" className="text-sm text-neutral-600 hover:text-neutral-900">
            ← All in-kind donations
          </a>
          <PrintButton />
        </div>

        <InKindCertificate donation={bundle.donation} items={bundle.items} />
      </div>

      <style>{`
        @media print {
          @page { size: letter landscape; margin: 0.4in; }
        }
      `}</style>
    </div>
  )
}
