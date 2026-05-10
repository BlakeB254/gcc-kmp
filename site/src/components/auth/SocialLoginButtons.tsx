import Link from 'next/link'

interface SocialLoginButtonsProps {
  mode: 'signin' | 'signup'
  returnTo?: string
}

function oauthHref(provider: 'google' | 'apple', mode: 'signin' | 'signup', returnTo?: string) {
  const params = new URLSearchParams({
    provider,
    role: 'worker',
    mode,
  })
  if (returnTo) params.set('returnTo', returnTo)
  return `/api/auth/web/start?${params.toString()}`
}

export function SocialLoginButtons({ mode, returnTo = '/worker/dashboard' }: SocialLoginButtonsProps) {
  const action = mode === 'signup' ? 'Sign up' : 'Continue'
  const appleConfigured = process.env.NEXT_PUBLIC_ENABLE_APPLE_WEB_OAUTH === 'true'

  return (
    <div className="space-y-3">
      {appleConfigured && (
        <Link
          href={oauthHref('apple', mode, returnTo)}
          className="flex w-full items-center justify-center rounded-xl bg-black px-4 py-3 text-sm font-semibold text-white transition-colors hover:bg-neutral-800"
        >
          {action} with Apple
        </Link>
      )}
      <Link
        href={oauthHref('google', mode, returnTo)}
        className="flex w-full items-center justify-center gap-2 rounded-xl border border-neutral-300 bg-white px-4 py-3 text-sm font-semibold text-neutral-800 transition-colors hover:bg-neutral-50"
      >
        <span aria-hidden="true" className="font-bold text-brand-green-700">G</span>
        {action} with Google
      </Link>
    </div>
  )
}
