import { redirect } from 'next/navigation'

// /worker/benefits was folded into the combined /how-it-works page.
// Old bookmarks + links keep working and land on the Workers tab.
export default function WorkerBenefitsAliasPage() {
  redirect('/how-it-works?tab=workers')
}
