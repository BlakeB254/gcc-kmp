/**
 * Stripe Connect onboarding refresh landing. Stripe sends the worker here
 * when their onboarding link expired mid-flow. Links are minted with the
 * worker's mobile auth (POST /api/workers/me/connect/onboard), so the fix
 * is always: go back to the app and tap "Set up payouts" again.
 */
export default function ConnectRefreshPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-neutral-950 px-6 text-center text-white">
      <span className="text-5xl">⏳</span>
      <h1 className="text-2xl font-bold">That setup link expired</h1>
      <p className="max-w-sm text-neutral-300">
        Head back to the Global Community Cleaners app and tap
        <span className="font-semibold"> Set up payouts</span> again — the app
        will open a fresh Stripe link for you.
      </p>
    </main>
  )
}
