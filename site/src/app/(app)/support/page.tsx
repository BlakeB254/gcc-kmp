'use client'

import { useEffect, useState, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import {
  MessageSquare,
  Plus,
  Clock,
  ChevronRight,
  AlertTriangle,
  Bug,
  CreditCard,
  Shield,
  Lightbulb,
  Sparkles,
  HelpCircle,
  RefreshCw,
  X,
  Send,
  CheckCircle2,
  AlertCircle,
  Hourglass,
} from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'

interface Ticket {
  id: number
  category: string
  subject: string
  description: string
  status: string
  priority: string
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
}

const categoryConfig: Record<string, { label: string; icon: any; color: string; description: string }> = {
  safety_concern: { label: 'Safety Concern', icon: Shield, color: 'bg-red-100 text-red-700', description: 'Report a safety issue or concern' },
  app_issue: { label: 'App Issue', icon: Bug, color: 'bg-orange-100 text-orange-700', description: 'Technical problems with the app' },
  verification_problem: { label: 'Verification Problem', icon: AlertTriangle, color: 'bg-yellow-100 text-yellow-700', description: 'Issues with identity verification' },
  payment_issue: { label: 'Payment Issue', icon: CreditCard, color: 'bg-purple-100 text-purple-700', description: 'Problems with payments or payouts' },
  general_improvement: { label: 'General Improvement', icon: Lightbulb, color: 'bg-blue-100 text-blue-700', description: 'Suggest how we can improve' },
  feature_request: { label: 'Feature Request', icon: Sparkles, color: 'bg-green-100 text-green-700', description: 'Request a new feature' },
  other: { label: 'Other', icon: HelpCircle, color: 'bg-neutral-100 text-neutral-700', description: 'Something else not listed' },
}

const statusConfig: Record<string, { label: string; icon: any; color: string }> = {
  open: { label: 'Open', icon: AlertCircle, color: 'bg-blue-100 text-blue-700' },
  in_progress: { label: 'In Progress', icon: Hourglass, color: 'bg-yellow-100 text-yellow-700' },
  waiting_response: { label: 'Waiting for Response', icon: MessageSquare, color: 'bg-purple-100 text-purple-700' },
  resolved: { label: 'Resolved', icon: CheckCircle2, color: 'bg-green-100 text-green-700' },
  closed: { label: 'Closed', icon: CheckCircle2, color: 'bg-neutral-100 text-neutral-700' },
}

export default function SupportPage() {
  const router = useRouter()
  const [tickets, setTickets] = useState<Ticket[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showNewTicketModal, setShowNewTicketModal] = useState(false)
  const [newTicket, setNewTicket] = useState({ category: 'general_improvement', subject: '', description: '' })
  const [isSubmitting, setIsSubmitting] = useState(false)

  const fetchTickets = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/feedback/tickets')
      const data = await res.json()

      if (!res.ok) {
        if (res.status === 401) {
          router.push('/worker/login')
          return
        }
        throw new Error(data.error || 'Failed to load tickets')
      }

      setTickets(data.tickets)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load tickets')
    } finally {
      setIsLoading(false)
    }
  }, [router])

  useEffect(() => {
    fetchTickets()
  }, [fetchTickets])

  const handleCreateTicket = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!newTicket.subject.trim() || !newTicket.description.trim()) return

    setIsSubmitting(true)
    try {
      const res = await fetch('/api/feedback/tickets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newTicket),
      })
      const data = await res.json()

      if (!res.ok) throw new Error(data.error || 'Failed to create ticket')

      setShowNewTicketModal(false)
      setNewTicket({ category: 'general_improvement', subject: '', description: '' })
      fetchTickets()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create ticket')
    } finally {
      setIsSubmitting(false)
    }
  }

  const openTickets = tickets.filter((t) => t.status !== 'resolved' && t.status !== 'closed')
  const closedTickets = tickets.filter((t) => t.status === 'resolved' || t.status === 'closed')

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50">
      {/* Header */}
      <header className="bg-white border-b border-neutral-200 sticky top-0 z-40">
        <div className="section-container py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link href="/" className="flex items-center gap-2">
                <Logo variant="short" size="sm" linkToHome={false} />
              </Link>
              <div className="hidden sm:block h-6 w-px bg-neutral-200" />
              <h1 className="hidden sm:block text-lg font-semibold text-neutral-900">
                Support & Feedback
              </h1>
            </div>

            <div className="flex items-center gap-3">
              <button
                onClick={fetchTickets}
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
                title="Refresh"
              >
                <RefreshCw className="w-5 h-5" />
              </button>
              <button
                onClick={() => setShowNewTicketModal(true)}
                className="btn-primary flex items-center gap-2 py-2 px-4 text-sm"
              >
                <Plus className="w-4 h-4" />
                <span className="hidden sm:inline">New Ticket</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      <main className="section-container py-8">
        <div className="max-w-4xl mx-auto">
          {/* Intro Card */}
          <div className="bg-gradient-to-r from-brand-green-500 to-brand-green-600 rounded-2xl p-6 mb-8 text-white">
            <h2 className="text-xl font-bold mb-2">We're Here to Help</h2>
            <p className="text-brand-green-100 mb-4">
              Have an issue, suggestion, or feedback? Let us know and we'll get back to you as soon as possible.
              Your input helps us build a better GCC for everyone.
            </p>
            <div className="flex flex-wrap gap-3">
              <Link
                href="/community"
                className="inline-flex items-center gap-2 px-4 py-2 bg-white/20 hover:bg-white/30 rounded-lg text-sm font-medium transition-colors"
              >
                <MessageSquare className="w-4 h-4" />
                Community Forum
              </Link>
              <Link
                href="/faq"
                className="inline-flex items-center gap-2 px-4 py-2 bg-white/20 hover:bg-white/30 rounded-lg text-sm font-medium transition-colors"
              >
                <HelpCircle className="w-4 h-4" />
                FAQ
              </Link>
            </div>
          </div>

          {/* Error State */}
          {error && (
            <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
              {error}
            </div>
          )}

          {/* Loading State */}
          {isLoading ? (
            <div className="flex flex-col items-center justify-center py-16">
              <div className="w-10 h-10 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin mb-4" />
              <p className="text-neutral-500">Loading tickets...</p>
            </div>
          ) : (
            <>
              {/* Open Tickets */}
              <div className="mb-8">
                <h3 className="text-lg font-semibold text-neutral-900 mb-4 flex items-center gap-2">
                  <AlertCircle className="w-5 h-5 text-blue-500" />
                  Open Tickets ({openTickets.length})
                </h3>

                {openTickets.length === 0 ? (
                  <div className="text-center py-12 bg-white rounded-2xl border border-neutral-200">
                    <CheckCircle2 className="w-12 h-12 text-green-400 mx-auto mb-4" />
                    <h3 className="text-lg font-semibold text-neutral-900 mb-2">No open tickets</h3>
                    <p className="text-neutral-500 mb-6">You don't have any active support requests.</p>
                    <button
                      onClick={() => setShowNewTicketModal(true)}
                      className="btn-primary inline-flex items-center gap-2"
                    >
                      <Plus className="w-4 h-4" />
                      Submit Feedback
                    </button>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {openTickets.map((ticket) => {
                      const catConfig = categoryConfig[ticket.category] || categoryConfig.other
                      const statConfig = statusConfig[ticket.status] || statusConfig.open
                      const CatIcon = catConfig.icon
                      const StatIcon = statConfig.icon
                      return (
                        <Link
                          key={ticket.id}
                          href={`/support/ticket/${ticket.id}`}
                          className="block bg-white rounded-xl border border-neutral-200 p-5 hover:shadow-lg hover:border-brand-green-200 transition-all group"
                        >
                          <div className="flex items-start justify-between gap-4">
                            <div className="flex-1 min-w-0">
                              <div className="flex flex-wrap items-center gap-2 mb-2">
                                <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${catConfig.color}`}>
                                  <CatIcon className="w-3 h-3" />
                                  {catConfig.label}
                                </span>
                                <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${statConfig.color}`}>
                                  <StatIcon className="w-3 h-3" />
                                  {statConfig.label}
                                </span>
                                {ticket.priority === 'urgent' && (
                                  <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded-full text-xs font-medium">
                                    Urgent
                                  </span>
                                )}
                              </div>
                              <h4 className="font-semibold text-neutral-900 mb-1 group-hover:text-brand-green-600 transition-colors">
                                {ticket.subject}
                              </h4>
                              <p className="text-sm text-neutral-500 flex items-center gap-1">
                                <Clock className="w-3.5 h-3.5" />
                                {formatDistanceToNow(new Date(ticket.createdAt), { addSuffix: true })}
                              </p>
                            </div>
                            <ChevronRight className="w-5 h-5 text-neutral-400 group-hover:text-brand-green-500 transition-colors" />
                          </div>
                        </Link>
                      )
                    })}
                  </div>
                )}
              </div>

              {/* Closed Tickets */}
              {closedTickets.length > 0 && (
                <div>
                  <h3 className="text-lg font-semibold text-neutral-900 mb-4 flex items-center gap-2">
                    <CheckCircle2 className="w-5 h-5 text-green-500" />
                    Resolved ({closedTickets.length})
                  </h3>
                  <div className="space-y-3 opacity-75">
                    {closedTickets.slice(0, 5).map((ticket) => {
                      const catConfig = categoryConfig[ticket.category] || categoryConfig.other
                      const CatIcon = catConfig.icon
                      return (
                        <Link
                          key={ticket.id}
                          href={`/support/ticket/${ticket.id}`}
                          className="block bg-white rounded-xl border border-neutral-200 p-4 hover:shadow-md transition-all group"
                        >
                          <div className="flex items-center justify-between gap-4">
                            <div className="flex items-center gap-3">
                              <div className={`p-2 rounded-lg ${catConfig.color}`}>
                                <CatIcon className="w-4 h-4" />
                              </div>
                              <div>
                                <h4 className="font-medium text-neutral-700 group-hover:text-brand-green-600 transition-colors">
                                  {ticket.subject}
                                </h4>
                                <p className="text-xs text-neutral-500">
                                  Resolved {ticket.resolvedAt ? formatDistanceToNow(new Date(ticket.resolvedAt), { addSuffix: true }) : ''}
                                </p>
                              </div>
                            </div>
                            <ChevronRight className="w-5 h-5 text-neutral-400" />
                          </div>
                        </Link>
                      )
                    })}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </main>

      {/* New Ticket Modal */}
      {showNewTicketModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setShowNewTicketModal(false)} />
          <div className="relative bg-white rounded-2xl shadow-xl max-w-lg w-full max-h-[90vh] overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-neutral-200 px-6 py-4 flex items-center justify-between rounded-t-2xl">
              <h2 className="text-xl font-bold text-neutral-900">Submit Feedback</h2>
              <button
                onClick={() => setShowNewTicketModal(false)}
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleCreateTicket} className="p-6 space-y-5">
              {/* Category */}
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  What is this about?
                </label>
                <div className="grid grid-cols-2 gap-2">
                  {Object.entries(categoryConfig).map(([key, config]) => {
                    const Icon = config.icon
                    const isSelected = newTicket.category === key
                    return (
                      <button
                        key={key}
                        type="button"
                        onClick={() => setNewTicket({ ...newTicket, category: key })}
                        className={`flex items-center gap-2 p-3 rounded-xl border-2 text-left transition-all ${
                          isSelected
                            ? 'border-brand-green-500 bg-brand-green-50'
                            : 'border-neutral-200 hover:border-neutral-300'
                        }`}
                      >
                        <div className={`p-1.5 rounded-lg ${config.color}`}>
                          <Icon className="w-4 h-4" />
                        </div>
                        <span className="text-sm font-medium text-neutral-700">{config.label}</span>
                      </button>
                    )
                  })}
                </div>
              </div>

              {/* Subject */}
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  Subject
                </label>
                <input
                  type="text"
                  value={newTicket.subject}
                  onChange={(e) => setNewTicket({ ...newTicket, subject: e.target.value })}
                  placeholder="Brief summary of your issue or feedback"
                  maxLength={255}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-green-500 focus:border-transparent transition-all"
                  required
                />
              </div>

              {/* Description */}
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  Description
                </label>
                <textarea
                  value={newTicket.description}
                  onChange={(e) => setNewTicket({ ...newTicket, description: e.target.value })}
                  placeholder="Please provide as much detail as possible..."
                  rows={5}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-green-500 focus:border-transparent transition-all resize-none"
                  required
                />
              </div>

              {/* Submit */}
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={() => setShowNewTicketModal(false)}
                  className="flex-1 py-3 px-4 border border-neutral-300 text-neutral-700 font-medium rounded-xl hover:bg-neutral-50 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting || !newTicket.subject.trim() || !newTicket.description.trim()}
                  className="flex-1 py-3 px-4 bg-brand-green-500 text-white font-semibold rounded-xl hover:bg-brand-green-600 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center justify-center gap-2"
                >
                  {isSubmitting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      Submitting...
                    </>
                  ) : (
                    <>
                      <Send className="w-4 h-4" />
                      Submit
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
