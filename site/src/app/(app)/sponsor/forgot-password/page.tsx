import Link from 'next/link'
import { ForgotPasswordForm } from '@/components/auth/ForgotPasswordForm'

export default function SponsorForgotPasswordPage() {
  return (
    <ForgotPasswordForm
      apiEndpoint="/api/sponsors/forgot-password"
      loginPath="/sponsor/login"
      userType="sponsor"
      extraLinks={
        <p className="text-center text-neutral-500 text-sm mt-6">
          Just want to sponsor a block?{' '}
          <Link href="/sponsor" className="text-brand-green-600 hover:underline">
            Sponsor without an account
          </Link>
        </p>
      }
    />
  )
}
