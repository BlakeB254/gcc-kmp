'use client'

export function PrintButton({ label = 'Print / Save as PDF' }: { label?: string }) {
  return (
    <button
      type="button"
      onClick={() => window.print()}
      className="rounded-md bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-800"
    >
      {label}
    </button>
  )
}
