'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ANDROID_TESTING_OPT_IN_URL,
  TESTERS_REQUIRED,
  TEST_DAYS,
  type TesterAction,
  type TesterProgress,
  type TesterStatus,
} from '@/lib/android-testers'

interface Tester {
  id: number
  email: string
  name: string | null
  source: string | null
  status: TesterStatus
  addedAt: string | null
  inviteSentAt: string | null
  joinedAt: string | null
  createdAt: string
}

const STATUS_LABEL: Record<TesterStatus, string> = {
  new: 'Waiting — not on Play list yet',
  added: 'On Play list',
  joined: 'Joined the test',
  removed: 'Removed',
}

const STATUS_STYLE: Record<TesterStatus, string> = {
  new: 'bg-amber-950/60 text-amber-300',
  added: 'bg-sky-950/60 text-sky-300',
  joined: 'bg-emerald-950/60 text-emerald-300',
  removed: 'bg-neutral-800 text-neutral-400',
}

const day = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—')

export default function AdminAndroidTestersPage() {
  const [testers, setTesters] = useState<Tester[]>([])
  const [progress, setProgress] = useState<TesterProgress | null>(null)
  const [playConsoleEmails, setPlayConsoleEmails] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  // The signups whose addresses were copied for Play Console. The invite goes
  // to these and only these: the list reloads after every action, so anyone
  // who signed up in between was never pasted, and would get a dead link.
  const [copied, setCopied] = useState<{ ids: number[]; emails: string } | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/android-testers', { headers: authHeaders })
      if (!res.ok) throw new Error(`Testers: ${res.status}`)
      const body = await res.json()
      setTesters(body.data || [])
      setProgress(body.progress || null)
      setPlayConsoleEmails(body.playConsoleEmails || '')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    load()
  }, [load])

  async function run(action: TesterAction, ids: number[]) {
    if (ids.length === 0) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const res = await fetch('/api/admin/android-testers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders },
        body: JSON.stringify({ action, ids }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(body.error || `Status ${res.status}`)
      if (body.failedEmails?.length) {
        setError(`Invite email failed for: ${body.failedEmails.join(', ')}. Use “Re-send link”.`)
      } else if (action === 'invite') {
        setNotice(`Sent the join link to ${body.updated} tester${body.updated === 1 ? '' : 's'}.`)
      }
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  async function copyEmails() {
    const waiting = testers.filter((t) => t.status === 'new')
    const snapshot = { ids: waiting.map((t) => t.id), emails: waiting.map((t) => t.email).join(', ') }
    setCopied(snapshot)
    try {
      await navigator.clipboard.writeText(snapshot.emails)
      setNotice('Copied. Paste into Play Console, save, then come back and press “Send join link”.')
    } catch {
      setError('Could not copy — select the addresses in the box and copy them by hand.')
    }
  }

  const waitingIds = testers.filter((t) => t.status === 'new').map((t) => t.id)
  // Of what was copied, those still waiting (a row may have been removed since).
  const inviteIds = copied ? copied.ids.filter((id) => waitingIds.includes(id)) : []
  const arrivedSinceCopy = copied ? waitingIds.length - inviteIds.length : 0

  return (
    <div className="max-w-5xl p-6 text-white">
      <h1 className="text-xl font-semibold">Android Testers</h1>
      <p className="mt-1 text-sm text-neutral-400">
        Google Play needs {TESTERS_REQUIRED} testers opted in for {TEST_DAYS} days in a row before
        the Android app can go public. Signups come from{' '}
        <a href="/android-beta" className="underline hover:text-white">
          /android-beta
        </a>
        .
      </p>

      {error && (
        <div className="mt-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">{error}</div>
      )}
      {notice && (
        <div className="mt-4 rounded-lg bg-emerald-950/40 px-4 py-2 text-sm text-emerald-300">
          {notice}
        </div>
      )}

      {progress && (
        <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            { label: 'Joined the test', value: `${progress.joined} / ${TESTERS_REQUIRED}` },
            { label: 'Still needed', value: progress.stillNeeded },
            { label: 'On Play list, not joined', value: progress.added },
            { label: 'Waiting to be added', value: progress.waiting },
          ].map((stat) => (
            <div key={stat.label} className="rounded-lg border border-neutral-800 bg-neutral-900 p-4">
              <div className="text-2xl font-semibold">{stat.value}</div>
              <div className="mt-1 text-xs text-neutral-400">{stat.label}</div>
            </div>
          ))}
        </div>
      )}
      <p className="mt-2 text-xs text-neutral-500">
        “Joined” is what you have marked here. Google&rsquo;s own count is in Play Console under
        Closed testing, and that is the number Google goes by. Recruit a few more than{' '}
        {TESTERS_REQUIRED}: anyone who leaves early stops counting.
      </p>

      <section className="mt-8 rounded-lg border border-neutral-800 bg-neutral-900 p-5">
        <h2 className="font-semibold">Add new signups to the test</h2>
        {waitingIds.length === 0 ? (
          <p className="mt-2 text-sm text-neutral-400">Nobody is waiting to be added.</p>
        ) : (
          <ol className="mt-3 list-decimal space-y-4 pl-5 text-sm text-neutral-300">
            <li>
              Copy the {waitingIds.length} new address{waitingIds.length === 1 ? '' : 'es'}:
              <textarea
                readOnly
                value={copied ? copied.emails : playConsoleEmails}
                rows={2}
                aria-label="New tester email addresses"
                onFocus={(e) => e.currentTarget.select()}
                className="mt-2 block w-full rounded-md border border-neutral-700 bg-neutral-950 p-2 font-mono text-xs text-neutral-200"
              />
              <button
                onClick={copyEmails}
                className="mt-2 rounded-md bg-neutral-700 px-3 py-1.5 text-xs font-medium hover:bg-neutral-600"
              >
                Copy addresses
              </button>
            </li>
            <li>
              In Play Console open <strong>Test and release → Testing → Closed testing</strong>,
              pick the track, open the <strong>Testers</strong> tab, edit the email list, paste
              them in and <strong>save</strong>. Paste, do not upload a file — a file replaces the
              whole list.
            </li>
            <li>
              Then send everyone their join link:
              <div>
                <button
                  onClick={async () => {
                    await run('invite', inviteIds)
                    setCopied(null)
                  }}
                  disabled={busy || inviteIds.length === 0}
                  className="mt-2 rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-semibold hover:bg-emerald-500 disabled:opacity-50"
                >
                  {copied
                    ? `I added them — send join link to ${inviteIds.length}`
                    : 'Copy the addresses first'}
                </button>
              </div>
              {arrivedSinceCopy > 0 && (
                <span className="mt-2 block text-xs text-amber-300">
                  {arrivedSinceCopy} more signed up after you copied. They are not included — copy
                  again once these are sent.
                </span>
              )}
              <span className="mt-2 block text-xs text-neutral-500">
                Only press this after saving in Play Console. The link ({ANDROID_TESTING_OPT_IN_URL})
                shows “not available” to anyone who is not on the list yet.
              </span>
            </li>
          </ol>
        )}
      </section>

      <section className="mt-8">
        <h2 className="font-semibold">All signups ({testers.length})</h2>
        {loading && <p className="mt-2 text-sm text-neutral-400">Loading…</p>}
        {!loading && testers.length === 0 && (
          <p className="mt-2 text-sm text-neutral-400">No signups yet.</p>
        )}
        {testers.length > 0 && (
          <div className="mt-3 overflow-x-auto rounded-lg border border-neutral-800">
            <table className="w-full text-left text-sm">
              <thead className="bg-neutral-900 text-xs text-neutral-400">
                <tr>
                  <th className="px-3 py-2 font-medium">Email</th>
                  <th className="px-3 py-2 font-medium">Name</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2 font-medium">Signed up</th>
                  <th className="px-3 py-2 font-medium">Link sent</th>
                  <th className="px-3 py-2 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {testers.map((t) => (
                  <tr key={t.id} className="border-t border-neutral-800">
                    <td className="px-3 py-2 font-mono text-xs">{t.email}</td>
                    <td className="px-3 py-2 text-neutral-300">{t.name || '—'}</td>
                    <td className="px-3 py-2">
                      <span className={`rounded px-2 py-0.5 text-xs ${STATUS_STYLE[t.status]}`}>
                        {STATUS_LABEL[t.status]}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-xs text-neutral-400">{day(t.createdAt)}</td>
                    <td className="px-3 py-2 text-xs text-neutral-400">
                      {t.status === 'added' && !t.inviteSentAt ? (
                        <span className="text-red-300">not sent</span>
                      ) : (
                        day(t.inviteSentAt)
                      )}
                    </td>
                    <td className="space-x-3 whitespace-nowrap px-3 py-2 text-xs">
                      {t.status === 'added' && (
                        <>
                          <button
                            onClick={() => run('joined', [t.id])}
                            disabled={busy}
                            className="text-emerald-400 hover:underline disabled:opacity-50"
                          >
                            Mark joined
                          </button>
                          <button
                            onClick={() => run('invite', [t.id])}
                            disabled={busy}
                            className="text-sky-400 hover:underline disabled:opacity-50"
                          >
                            Re-send link
                          </button>
                        </>
                      )}
                      {t.status === 'removed' ? (
                        <button
                          onClick={() => run('restore', [t.id])}
                          disabled={busy}
                          className="text-neutral-300 hover:underline disabled:opacity-50"
                        >
                          Restore
                        </button>
                      ) : (
                        <button
                          onClick={() => run('remove', [t.id])}
                          disabled={busy}
                          className="text-neutral-500 hover:text-red-400 hover:underline disabled:opacity-50"
                        >
                          Remove
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
