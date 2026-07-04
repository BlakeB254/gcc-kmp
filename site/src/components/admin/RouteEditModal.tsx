'use client'

/**
 * Small modal for editing a route's display fields from the admin routes
 * list. Saves via PUT /api/admin/routes/[routeId] — payoutAmount is entered
 * in DOLLARS (the endpoint converts to cents).
 */

import { useState } from 'react'

export interface RouteEditValues {
  displayName: string
  payoutDollars: number
  description: string
}

interface Props {
  routeCode: string
  initial: RouteEditValues
  saving: boolean
  onSave: (values: RouteEditValues) => void
  onClose: () => void
}

export function RouteEditModal({ routeCode, initial, saving, onSave, onClose }: Props) {
  const [displayName, setDisplayName] = useState(initial.displayName)
  const [payout, setPayout] = useState(String(initial.payoutDollars))
  const [description, setDescription] = useState(initial.description)
  const [error, setError] = useState('')

  function handleSave() {
    const name = displayName.trim()
    if (!name) {
      setError('Name is required.')
      return
    }
    const payoutDollars = Number(payout)
    if (!Number.isFinite(payoutDollars) || payoutDollars < 0) {
      setError('Payout must be a non-negative dollar amount.')
      return
    }
    setError('')
    onSave({ displayName: name, payoutDollars, description: description.trim() })
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !saving) onClose()
      }}
    >
      <div className="w-full max-w-md rounded-lg border border-neutral-800 bg-neutral-950 p-5 space-y-4 shadow-xl">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-white">
            Edit route <span className="font-mono text-green-400">{routeCode}</span>
          </h2>
          <button
            onClick={onClose}
            disabled={saving}
            className="text-neutral-400 hover:text-white transition-colors text-sm disabled:opacity-40"
          >
            ✕
          </button>
        </div>

        <label className="block space-y-1">
          <span className="text-xs text-neutral-400">Name</span>
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className="w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
          />
        </label>

        <label className="block space-y-1">
          <span className="text-xs text-neutral-400">Payout (dollars)</span>
          <input
            type="number"
            min={0}
            step={1}
            value={payout}
            onChange={(e) => setPayout(e.target.value)}
            className="w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
          />
        </label>

        <label className="block space-y-1">
          <span className="text-xs text-neutral-400">Description</span>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            className="w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500 resize-y"
          />
        </label>

        {error && <div className="text-xs text-red-400">{error}</div>}

        <div className="flex gap-2 pt-1">
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex-1 py-2 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 disabled:opacity-50 transition-colors"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            onClick={onClose}
            disabled={saving}
            className="flex-1 py-2 rounded-md bg-neutral-800 text-neutral-300 text-sm hover:bg-neutral-700 disabled:opacity-40 transition-colors"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  )
}
