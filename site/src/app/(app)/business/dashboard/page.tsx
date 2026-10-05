import { redirect } from 'next/navigation'

// This used to be a public mock dashboard of invented sponsor data. Real
// sponsors see their own activity at /sponsor/dashboard after signing in.
export default function BusinessDashboardPage() {
  redirect('/sponsor/dashboard')
}
