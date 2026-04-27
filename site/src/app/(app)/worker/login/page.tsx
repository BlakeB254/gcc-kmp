import { redirect } from 'next/navigation'

// /worker/login is now an alias of the unified /login page.
// Old bookmarks + email links keep working; everyone lands in the same form.
export default function WorkerLoginAliasPage() {
  redirect('/login')
}
