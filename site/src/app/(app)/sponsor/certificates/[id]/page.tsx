import { notFound } from 'next/navigation'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { sponsorDeliverablePackages, sponsors } from '@/db/schema'
import { formatDollars } from '@/lib/sponsor-deliverables'
import { PrintButton } from '@/components/sponsor/PrintButton'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export default async function SponsorCertificatePage({
  params,
}: {
  params: Promise<{ id: string }>
}) {
  const { id: idRaw } = await params
  const id = Number(idRaw)
  if (!Number.isFinite(id)) notFound()

  const [pkg] = await db
    .select()
    .from(sponsorDeliverablePackages)
    .where(eq(sponsorDeliverablePackages.id, id))
    .limit(1)
  if (!pkg) notFound()

  const [sponsor] = await db
    .select()
    .from(sponsors)
    .where(eq(sponsors.id, pkg.sponsorId))
    .limit(1)
  if (!sponsor) notFound()

  const display = sponsor.businessName || sponsor.name
  const date = new Date(pkg.createdAt).toLocaleDateString('en-US', { dateStyle: 'long' })
  const amount = formatDollars(pkg.amountCents)

  return (
    <div className="min-h-screen bg-neutral-100 py-12 print:bg-white print:p-0">
      <div className="mx-auto max-w-[1100px] px-4 print:px-0">
        <div className="mb-6 flex items-center justify-between print:hidden">
          <a href="/sponsor/wall" className="text-sm text-neutral-600 hover:text-neutral-900">
            ← Sponsor wall
          </a>
          <PrintButton />
        </div>

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
                className="h-44 w-auto object-contain"
              />
              <h1 className="mt-4 font-serif text-5xl font-bold tracking-tight text-neutral-900">
                Certificate of Appreciation
              </h1>
              <p className="mt-3 text-sm uppercase tracking-widest text-neutral-500">
                Presented to
              </p>
            </div>

            <div className="my-6">
              <p className="font-serif text-5xl italic text-emerald-900">{display}</p>
              <div className="mx-auto mt-5 h-px w-2/3 bg-neutral-300" />
              <p className="mt-5 max-w-2xl text-balance text-base leading-relaxed text-neutral-700">
                In grateful recognition of a generous {amount} sponsorship of Global
                Community Cleaners — funding clean blocks and paid local work in
                North Lawndale and beyond. Your support puts every dollar directly
                into the streets and into the pockets of the residents who clean them.
              </p>
            </div>

            <div className="flex w-full items-end justify-between">
              <div className="text-left">
                <p className="border-t border-neutral-400 pt-2 font-serif text-xs uppercase tracking-widest text-neutral-500">
                  Date
                </p>
                <p className="mt-1 text-sm text-neutral-800">{date}</p>
              </div>
              <div className="text-center">
                <p className="font-serif text-xl italic text-emerald-900">Global Community Cleaners NFP</p>
                <p className="text-[10px] uppercase tracking-widest text-neutral-500">501(c)(3)</p>
              </div>
              <div className="text-right">
                <p className="border-t border-neutral-400 pt-2 font-serif text-xs uppercase tracking-widest text-neutral-500">
                  Certificate ID
                </p>
                <p className="mt-1 text-sm text-neutral-800">GCC-CERT-{String(pkg.id).padStart(5, '0')}</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <style>{`
        @media print {
          @page { size: letter landscape; margin: 0.4in; }
        }
      `}</style>
    </div>
  )
}
