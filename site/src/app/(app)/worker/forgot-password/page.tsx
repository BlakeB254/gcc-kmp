import { ForgotPasswordForm } from '@/components/auth/ForgotPasswordForm'

export default function WorkerForgotPasswordPage() {
  return (
    <ForgotPasswordForm
      apiEndpoint="/api/workers/forgot-password"
      loginPath="/worker/login"
      userType="worker"
    />
  )
}
