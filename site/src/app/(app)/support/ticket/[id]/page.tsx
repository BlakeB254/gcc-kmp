'use client'

import { useEffect, useState, useCallback } from 'react'
import { useRouter, useParams } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import {
  ArrowLeft,
  Send,
  RefreshCw,
  Clock,
  CheckCircle2,
  AlertCircle,
  Hourglass,
  MessageSquare,
  User,
  Shield,
  Bug,
  AlertTriangle,
  CreditCard,
  Lightbulb,
  Sparkles,
  HelpCircle,
} from 'lucide-react'
import { formatDistanceToNow, format } from 'date-fns'

interface Reply {
  id: number
  isAdminReply: boolean
  replierName: string
  message: string
  createdAt: string
}

interface Ticket {
  id: number
  category: string
  subject: string
  description: string
  status: string
  priority: string
  resolutionNotes: string | null
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
}

const categoryConfig: Record<string, { label: string; icon: any; color: string }> = {
  safety_concern: { label: 'Safety Concern', icon: Shield, color: 'bg-red-100 text-red-700' },
  app_issue: { label: 'App Issue', icon: Bug, color: 'bg-orange-100 text-orange-700' },
  verification_problem: { label: 'Verification Problem', icon: AlertTriangle, color: 'bg-yellow-100 text-yellow-700' },
  payment_issue: { label: 'Payment Issue', icon: CreditCard, color: 'bg-purple-100 text-purple-700' },
  general_improvement: { label: 'General Improvement', icon: Lightbulb, color: 'bg-blue-100 text-blue-700' },
  feature_request: { label: 'Feature Request', icon: Sparkles, color: 'bg-green-100 text-green-700' },
  other: { label: 'Other', icon: HelpCircle, color: 'bg-neutral-100 text-neutral-700' },
}

const statusConfig: Record<string, { label: string; icon: any; color: string; bgColor: string }> = {
  open: { label: 'Open', icon: AlertCircle, color: 'text-blue-700', bgColor: 'bg-blue-100' },
  in_progress: { label: 'In Progress', icon: Hourglass, color: 'text-yellow-700', bgColor: 'bg-yellow-100' },
  waiting_response: { label: 'Waiting for Your Response', icon: MessageSquare, color: 'text-purple-700', bgColor: 'bg-purple-100' },
  resolved: { label: 'Resolved', icon: CheckCircle2, color: 'text-green-700', bgColor: 'bg-green-100' },
  closed: { label: 'Closed', icon: CheckCircle2, color: 'text-neutral-700', bgColor: 'bg-neutral-100' },
}

export default function TicketDetailPage() {
  const router = useRouter()
  const params = useParams()
  const ticketId = params.id as string

  const [ticket, setTicket] = useState<Ticket | null>(null)
  const [replies, setReplies] = useState<Reply[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [replyMessage, setReplyMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  const fetchTicket = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/feedback/tickets/${ticketId}`)
      const data = await res.json()

      if (!res.ok) {
        if (res.status === 401) {
          router.push('/worker/login')
          return
        }
        throw new Error(data.error || 'Failed to load ticket')
      }

      setTicket(data.ticket)
      setReplies(data.replies)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load ticket')
    } finally {
      setIsLoading(false)
    }
  }, [ticketId, router])

  useEffect(() => {
    fetchTicket()
  }, [fetchTicket])

  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!replyMessage.trim() || !ticket) return

    setIsSubmitting(true)
    try {
      const res = await fetch(`/api/feedback/tickets/${ticketId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: replyMessage.trim() }),
      })
      const data = await res.json()

      if (!res.ok) throw new Error(data.error || 'Failed to send reply')

      setReplies([...replies, data.reply])
      setReplyMessage('')
      // Update status if it was waiting for response
      if (ticket.status === 'waiting_response') {
        setTicket({ ...ticket, status: 'open' })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send reply')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-500">Loading ticket...</p>
        </div>
      </div>
    )
  }

  if (error || !ticket) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-red-500 mb-4">{error || 'Ticket not found'}</p>
          <Link href="/support" className="text-brand-green-600 hover:underline">
            Return to support
          </Link>
        </div>
      </div>
    )
  }

  const catConfig = categoryConfig[ticket.category] || categoryConfig.other
  const statConfig = statusConfig[ticket.status] || statusConfig.open
  const CatIcon = catConfig.icon
  const StatIcon = statConfig.icon
  const isClosed = ticket.status === 'closed' || ticket.status === 'resolved'

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50">
      {/* Header */}
      <header className="bg-white border-b border-neutral-200 sticky top-0 z-40">
        <div className="section-container py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link
                href="/support"
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
              >
                <ArrowLeft className="w-5 h-5" />
              </Link>
              <div>
                <h1 className="text-lg font-semibold text-neutral-900 truncate max-w-[200px] sm:max-w-none">
                  Ticket #{ticket.id}
                </h1>
                <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${statConfig.bgColor} ${statConfig.color}`}>
                  <StatIcon className="w-3 h-3" />
                  {statConfig.label}
                </span>
              </div>
            </div>

            <button
              onClick={fetchTicket}
              className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
              title="Refresh"
            >
              <RefreshCw className="w-5 h-5" />
            </button>
          </div>
        </div>
      </header>

      <main className="section-container py-8">
        <div className="max-w-3xl mx-auto">
          {/* Ticket Details */}
          <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-6">
            <div className="p-6">
              {/* Category and Priority */}
              <div className="flex flex-wrap items-center gap-2 mb-4">
                <span className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-medium ${catConfig.color}`}>
                  <CatIcon className="w-4 h-4" />
                  {catConfig.label}
                </span>
                {ticket.priority === 'urgent' && (
                  <span className="px-3 py-1 bg-red-100 text-red-700 rounded-full text-sm font-medium">
                    Urgent Priority
                  </span>
                )}
                {ticket.priority === 'high' && (
                  <span className="px-3 py-1 bg-orange-100 text-orange-700 rounded-full text-sm font-medium">
                    High Priority
                  </span>
                )}
              </div>

              {/* Subject */}
              <h2 className="text-2xl font-bold text-neutral-900 mb-4">
                {ticket.subject}
              </h2>

              {/* Meta */}
              <div className="flex flex-wrap items-center gap-4 text-sm text-neutral-500 mb-6 pb-6 border-b border-neutral-100">
                <span className="flex items-center gap-1">
                  <Clock className="w-4 h-4" />
                  Submitted {format(new Date(ticket.createdAt), 'MMM d, yyyy h:mm a')}
                </span>
                {ticket.resolvedAt && (
                  <span className="flex items-center gap-1 text-green-600">
                    <CheckCircle2 className="w-4 h-4" />
                    Resolved {format(new Date(ticket.resolvedAt), 'MMM d, yyyy')}
                  </span>
                )}
              </div>

              {/* Description */}
              <div className="bg-neutral-50 rounded-xl p-4">
                <p className="text-neutral-700 whitespace-pre-wrap leading-relaxed">
                  {ticket.description}
                </p>
              </div>

              {/* Resolution Notes */}
              {ticket.resolutionNotes && (
                <div className="mt-4 bg-green-50 border border-green-200 rounded-xl p-4">
                  <h4 className="font-semibold text-green-800 mb-2 flex items-center gap-2">
                    <CheckCircle2 className="w-4 h-4" />
                    Resolution
                  </h4>
                  <p className="text-green-700 whitespace-pre-wrap">
                    {ticket.resolutionNotes}
                  </p>
                </div>
              )}
            </div>
          </div>

          {/* Conversation */}
          <div className="mb-6">
            <h3 className="text-lg font-semibold text-neutral-900 mb-4">
              Conversation ({replies.length})
            </h3>

            {replies.length === 0 ? (
              <div className="text-center py-8 bg-white rounded-xl border border-neutral-200">
                <MessageSquare className="w-10 h-10 text-neutral-300 mx-auto mb-2" />
                <p className="text-neutral-500">No replies yet. We'll get back to you soon!</p>
              </div>
            ) : (
              <div className="space-y-4">
                {replies.map((reply) => (
                  <div
                    key={reply.id}
                    className={`rounded-xl p-4 ${
                      reply.isAdminReply
                        ? 'bg-brand-green-50 border border-brand-green-200 ml-0 mr-8'
                        : 'bg-white border border-neutral-200 ml-8 mr-0'
                    }`}
                  >
                    <div className="flex items-center gap-2 mb-2">
                      <div className={`w-8 h-8 rounded-full flex items-center justify-center text-white text-sm font-bold ${
                        reply.isAdminReply
                          ? 'bg-gradient-to-br from-brand-green-500 to-brand-green-600'
                          : 'bg-gradient-to-br from-neutral-400 to-neutral-500'
                      }`}>
                        {reply.isAdminReply ? (
                          <Shield className="w-4 h-4" />
                        ) : (
                          reply.replierName.charAt(0).toUpperCase()
                        )}
                      </div>
                      <div>
                        <span className="font-medium text-neutral-900 text-sm">
                          {reply.isAdminReply ? 'GCC Support' : reply.replierName}
                        </span>
                        {reply.isAdminReply && (
                          <span className="ml-2 px-1.5 py-0.5 bg-brand-green-200 text-brand-green-800 rounded text-[10px] font-medium">
                            Staff
                          </span>
                        )}
                        <p className="text-xs text-neutral-500">
                          {formatDistanceToNow(new Date(reply.createdAt), { addSuffix: true })}
                        </p>
                      </div>
                    </div>
                    <p className="text-neutral-700 text-sm whitespace-pre-wrap ml-10">
                      {reply.message}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Reply Form */}
          {!isClosed ? (
            <div className="bg-white rounded-xl border border-neutral-200 p-4">
              <form onSubmit={handleSubmitReply}>
                <textarea
                  value={replyMessage}
                  onChange={(e) => setReplyMessage(e.target.value)}
                  placeholder="Write your reply..."
                  rows={3}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-green-500 focus:border-transparent transition-all resize-none"
                  required
                />
                <div className="flex items-center justify-end mt-3">
                  <button
                    type="submit"
                    disabled={isSubmitting || !replyMessage.trim()}
                    className="px-5 py-2 bg-brand-green-500 text-white font-semibold rounded-lg hover:bg-brand-green-600 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center gap-2"
                  >
                    {isSubmitting ? (
                      <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    ) : (
                      <Send className="w-4 h-4" />
                    )}
                    Send Reply
                  </button>
                </div>
              </form>
            </div>
          ) : (
            <div className="text-center py-6 bg-neutral-100 rounded-xl border border-neutral-200">
              <CheckCircle2 className="w-8 h-8 text-green-500 mx-auto mb-2" />
              <p className="text-neutral-600">This ticket has been {ticket.status}.</p>
              <Link
                href="/support"
                className="mt-3 inline-flex items-center gap-2 text-brand-green-600 hover:underline"
              >
                Submit a new ticket if you need more help
              </Link>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
