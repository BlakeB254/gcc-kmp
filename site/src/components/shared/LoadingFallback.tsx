export function LoadingFallback() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center">
      <div className="flex flex-col items-center gap-4">
        <div className="w-12 h-12 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin" />
        <p className="text-neutral-500">Loading...</p>
      </div>
    </div>
  )
}
