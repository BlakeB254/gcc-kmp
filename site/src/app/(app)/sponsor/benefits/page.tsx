import { redirect } from 'next/navigation'

// /sponsor/benefits was folded into the combined /how-it-works page.
// Old bookmarks + links keep working and land on the Sponsors tab.
export default function SponsorBenefitsAliasPage() {
  redirect('/how-it-works?tab=sponsors')
}
