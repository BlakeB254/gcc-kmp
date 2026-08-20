'use client'

/**
 * Generic admin confirmation dialog — same visual vocabulary as
 * RouteEditModal (neutral-950 modal on black/60 backdrop).
 *
 * - Pass `confirmLabel` for a two-button confirm/cancel flow; omit it for a
 *   single "Close" button (informational result dialogs).
 * - Pass `requireTypedText` to gate the confirm button behind a typed
 *   confirmation (destructive batch actions).
 */

import { useState, type ReactNode } from 'react'

interface Props {
  title: ReactNode
  body?: ReactNode
  confirmLabel?: string
  confirmTone?: 'danger' | 'primary'
  requireTypedText?: string
  busy?: boolean
  onConfirm?: () => void
  onClose: () => void
}

export function AdminConfirmDialog({
  title,
  body,
  confirmLabel,
  confirmTone = 'danger',
  requireTypedText,
  busy = false,
  onConfirm,
  onClose,
}: Props) {
  const [typed, setTyped] = useState('')
  const typedOk = !requireTypedText || typed.trim() === requireTypedText

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onClose()
      }}
    >
      <div className="w-full max-w-md rounded-lg border border-neutral-800 bg-neutral-950 p-5 space-y-4 shadow-xl">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-white">{title}</h2>
          <button
            onClick={onClose}
            disabled={busy}
            className="text-neutral-400 hover:text-white transition-colors text-sm disabled:opacity-40"
          >
            ✕
          </button>
        </div>

        {body && <div className="text-sm text-neutral-300 space-y-2">{body}</div>}

        {requireTypedText && (
          <label className="block space-y-1">
            <span className="text-xs text-neutral-400">
              Type <span className="font-mono text-white">{requireTypedText}</span> to confirm
            </span>
            <input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              disabled={busy}
              className="w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500 disabled:opacity-40"
            />
          </label>
        )}

        <div className="flex gap-2 pt-1">
          {confirmLabel && onConfirm ? (
            <>
              <button
                onClick={onConfirm}
                disabled={busy || !typedOk}
                className={`flex-1 py-2 rounded-md text-white text-sm font-medium disabled:opacity-50 transition-colors ${
                  confirmTone === 'danger'
                    ? 'bg-red-700 hover:bg-red-600'
                    : 'bg-green-600 hover:bg-green-500'
                }`}
              >
                {busy ? 'Working…' : confirmLabel}
              </button>
              <button
                onClick={onClose}
                disabled={busy}
                className="flex-1 py-2 rounded-md bg-neutral-800 text-neutral-300 text-sm hover:bg-neutral-700 disabled:opacity-40 transition-colors"
              >
                Cancel
              </button>
            </>
          ) : (
            <button
              onClick={onClose}
              disabled={busy}
              className="flex-1 py-2 rounded-md bg-neutral-800 text-neutral-300 text-sm hover:bg-neutral-700 disabled:opacity-40 transition-colors"
            >
              Close
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
