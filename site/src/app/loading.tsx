export default function Loading() {
  return (
    <main
      role="status"
      aria-label="Loading"
      className="flex min-h-[60vh] items-center justify-center"
    >
      <div className="h-10 w-10 animate-spin rounded-full border-2 border-gray-200 border-t-emerald-600" />
    </main>
  )
}
