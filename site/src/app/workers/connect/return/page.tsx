/**
 * Stripe Connect onboarding return landing. Stripe redirects the worker's
 * browser here after they finish (or exit) the hosted onboarding flow that
 * the mobile app opened. There is no web session to act on — the app's
 * Wallet screen re-fetches /api/workers/me/connect/status when it regains
 * foreground — so this page just closes the loop for the human.
 */
export default function ConnectReturnPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-neutral-950 px-6 text-center text-white">
      <span className="text-5xl">✅</span>
      <h1 className="text-2xl font-bold">Payout setup submitted</h1>
      <p className="max-w-sm text-neutral-300">
        You can close this window and return to the Global Community Cleaners
        app. Your Wallet tab will show your payout status — Stripe sometimes
        takes a minute to finish verifying.
      </p>
    </main>
  )
}
