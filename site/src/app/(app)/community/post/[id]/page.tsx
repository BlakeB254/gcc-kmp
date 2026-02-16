'use client'

import { useEffect, useState, useCallback } from 'react'
import { useRouter, useParams } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import {
  MessageSquare,
  Heart,
  Eye,
  Clock,
  ArrowLeft,
  Send,
  MoreVertical,
  Trash2,
  Flag,
  Reply,
  Users,
  Megaphone,
  Pin,
  RefreshCw,
} from 'lucide-react'
import { formatDistanceToNow, format } from 'date-fns'

interface Reply {
  id: number
  authorType: 'worker' | 'sponsor'
  authorId: number
  authorName: string
  parentReplyId: number | null
  content: string
  likeCount: number
  createdAt: string
  isOwner: boolean
  isLiked: boolean
}

interface Post {
  id: number
  authorType: 'worker' | 'sponsor'
  authorId: number
  authorName: string
  category: string
  title: string
  content: string
  status: string
  isPinned: boolean
  isAnnouncement: boolean
  viewCount: number
  replyCount: number
  likeCount: number
  lastActivityAt: string
  createdAt: string
  isOwner: boolean
  isLiked: boolean
}

const categoryLabels: Record<string, string> = {
  general: 'General',
  worker_tips: 'Worker Tips',
  sponsor_stories: 'Sponsor Stories',
  feature_requests: 'Feature Requests',
  local_news: 'Local News',
  announcements: 'Announcements',
}

export default function PostDetailPage() {
  const router = useRouter()
  const params = useParams()
  const postId = params.id as string

  const [post, setPost] = useState<Post | null>(null)
  const [replies, setReplies] = useState<Reply[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [replyContent, setReplyContent] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [replyingTo, setReplyingTo] = useState<Reply | null>(null)

  const fetchPost = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/forum/posts/${postId}`)
      const data = await res.json()

      if (!res.ok) {
        if (res.status === 401) {
          router.push('/worker/login')
          return
        }
        throw new Error(data.error || 'Failed to load post')
      }

      setPost(data.post)
      setReplies(data.replies)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load post')
    } finally {
      setIsLoading(false)
    }
  }, [postId, router])

  useEffect(() => {
    fetchPost()
  }, [fetchPost])

  const handleLikePost = async () => {
    if (!post) return
    try {
      const res = await fetch(`/api/forum/posts/${postId}/like`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      })
      const data = await res.json()
      if (res.ok) {
        setPost({
          ...post,
          isLiked: data.liked,
          likeCount: data.liked ? post.likeCount + 1 : post.likeCount - 1,
        })
      }
    } catch (err) {
      console.error('Like error:', err)
    }
  }

  const handleLikeReply = async (replyId: number) => {
    try {
      const res = await fetch(`/api/forum/posts/${postId}/like`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ replyId }),
      })
      const data = await res.json()
      if (res.ok) {
        setReplies(replies.map((r) => {
          if (r.id === replyId) {
            return {
              ...r,
              isLiked: data.liked,
              likeCount: data.liked ? r.likeCount + 1 : r.likeCount - 1,
            }
          }
          return r
        }))
      }
    } catch (err) {
      console.error('Like error:', err)
    }
  }

  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!replyContent.trim() || !post) return

    setIsSubmitting(true)
    try {
      const res = await fetch(`/api/forum/posts/${postId}/replies`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          content: replyContent.trim(),
          parentReplyId: replyingTo?.id || null,
        }),
      })
      const data = await res.json()

      if (!res.ok) throw new Error(data.error || 'Failed to add reply')

      setReplies([...replies, data.reply])
      setReplyContent('')
      setReplyingTo(null)
      setPost({ ...post, replyCount: post.replyCount + 1 })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add reply')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleDeletePost = async () => {
    if (!post || !confirm('Are you sure you want to delete this post?')) return

    try {
      const res = await fetch(`/api/forum/posts/${postId}`, { method: 'DELETE' })
      if (res.ok) {
        router.push('/community')
      }
    } catch (err) {
      console.error('Delete error:', err)
    }
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-500">Loading post...</p>
        </div>
      </div>
    )
  }

  if (error || !post) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-red-500 mb-4">{error || 'Post not found'}</p>
          <Link href="/community" className="text-brand-gold-600 hover:underline">
            Return to community
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50">
      {/* Header */}
      <header className="bg-white border-b border-neutral-200 sticky top-0 z-40">
        <div className="section-container py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link
                href="/community"
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
              >
                <ArrowLeft className="w-5 h-5" />
              </Link>
              <h1 className="text-lg font-semibold text-neutral-900 truncate max-w-[200px] sm:max-w-none">
                {post.title}
              </h1>
            </div>

            <div className="flex items-center gap-2">
              <button
                onClick={fetchPost}
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
                title="Refresh"
              >
                <RefreshCw className="w-5 h-5" />
              </button>
              {post.isOwner && (
                <button
                  onClick={handleDeletePost}
                  className="p-2 text-red-500 hover:text-red-700 hover:bg-red-50 rounded-lg transition-colors"
                  title="Delete post"
                >
                  <Trash2 className="w-5 h-5" />
                </button>
              )}
            </div>
          </div>
        </div>
      </header>

      <main className="section-container py-8">
        <div className="max-w-3xl mx-auto">
          {/* Post */}
          <article className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-8">
            <div className="p-6 sm:p-8">
              {/* Badges */}
              <div className="flex flex-wrap items-center gap-2 mb-4">
                {post.isPinned && (
                  <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-amber-100 text-amber-700 rounded-full text-xs font-medium">
                    <Pin className="w-3 h-3" /> Pinned
                  </span>
                )}
                {post.isAnnouncement && (
                  <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-red-100 text-red-700 rounded-full text-xs font-medium">
                    <Megaphone className="w-3 h-3" /> Announcement
                  </span>
                )}
                <span className="px-2 py-0.5 bg-neutral-100 text-neutral-700 rounded-full text-xs font-medium">
                  {categoryLabels[post.category] || post.category}
                </span>
              </div>

              {/* Title */}
              <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-4">
                {post.title}
              </h1>

              {/* Author */}
              <div className="flex items-center gap-3 mb-6 pb-6 border-b border-neutral-100">
                <div className="w-10 h-10 rounded-full bg-gradient-to-br from-brand-gold-400 to-brand-gold-600 flex items-center justify-center text-white font-bold">
                  {post.authorName.charAt(0).toUpperCase()}
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-neutral-900">{post.authorName}</span>
                    <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                      post.authorType === 'worker' ? 'bg-brand-gold-100 text-brand-gold-700' : 'bg-brand-green-100 text-brand-green-700'
                    }`}>
                      {post.authorType}
                    </span>
                  </div>
                  <span className="text-xs text-neutral-500">
                    {format(new Date(post.createdAt), 'MMM d, yyyy h:mm a')}
                  </span>
                </div>
              </div>

              {/* Content */}
              <div className="prose prose-neutral max-w-none mb-6">
                <p className="whitespace-pre-wrap text-neutral-700 leading-relaxed">
                  {post.content}
                </p>
              </div>

              {/* Actions */}
              <div className="flex items-center justify-between pt-4 border-t border-neutral-100">
                <div className="flex items-center gap-4 text-sm text-neutral-500">
                  <span className="flex items-center gap-1">
                    <Eye className="w-4 h-4" />
                    {post.viewCount} views
                  </span>
                  <span className="flex items-center gap-1">
                    <MessageSquare className="w-4 h-4" />
                    {post.replyCount} replies
                  </span>
                </div>

                <button
                  onClick={handleLikePost}
                  className={`flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition-colors ${
                    post.isLiked
                      ? 'bg-red-100 text-red-600'
                      : 'bg-neutral-100 text-neutral-600 hover:bg-red-50 hover:text-red-500'
                  }`}
                >
                  <Heart className={`w-4 h-4 ${post.isLiked ? 'fill-current' : ''}`} />
                  {post.likeCount}
                </button>
              </div>
            </div>
          </article>

          {/* Replies Section */}
          <div className="mb-6">
            <h2 className="text-lg font-semibold text-neutral-900 mb-4">
              Replies ({replies.length})
            </h2>

            {replies.length === 0 ? (
              <div className="text-center py-8 bg-white rounded-xl border border-neutral-200">
                <MessageSquare className="w-10 h-10 text-neutral-300 mx-auto mb-2" />
                <p className="text-neutral-500">No replies yet. Be the first to respond!</p>
              </div>
            ) : (
              <div className="space-y-4">
                {replies.map((reply) => (
                  <div
                    key={reply.id}
                    className={`bg-white rounded-xl border border-neutral-200 p-5 ${
                      reply.parentReplyId ? 'ml-8' : ''
                    }`}
                  >
                    {/* Reply Author */}
                    <div className="flex items-center gap-3 mb-3">
                      <div className="w-8 h-8 rounded-full bg-gradient-to-br from-neutral-300 to-neutral-400 flex items-center justify-center text-white text-sm font-bold">
                        {reply.authorName.charAt(0).toUpperCase()}
                      </div>
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-neutral-900 text-sm">{reply.authorName}</span>
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                            reply.authorType === 'worker' ? 'bg-brand-gold-100 text-brand-gold-700' : 'bg-brand-green-100 text-brand-green-700'
                          }`}>
                            {reply.authorType}
                          </span>
                        </div>
                        <span className="text-xs text-neutral-500">
                          {formatDistanceToNow(new Date(reply.createdAt), { addSuffix: true })}
                        </span>
                      </div>
                    </div>

                    {/* Reply Content */}
                    <p className="text-neutral-700 text-sm whitespace-pre-wrap mb-3">
                      {reply.content}
                    </p>

                    {/* Reply Actions */}
                    <div className="flex items-center gap-4">
                      <button
                        onClick={() => handleLikeReply(reply.id)}
                        className={`flex items-center gap-1 text-xs font-medium transition-colors ${
                          reply.isLiked ? 'text-red-500' : 'text-neutral-500 hover:text-red-500'
                        }`}
                      >
                        <Heart className={`w-3.5 h-3.5 ${reply.isLiked ? 'fill-current' : ''}`} />
                        {reply.likeCount}
                      </button>
                      {post.status === 'active' && (
                        <button
                          onClick={() => setReplyingTo(reply)}
                          className="flex items-center gap-1 text-xs font-medium text-neutral-500 hover:text-brand-gold-600 transition-colors"
                        >
                          <Reply className="w-3.5 h-3.5" />
                          Reply
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Reply Form */}
          {post.status === 'active' && (
            <div className="bg-white rounded-xl border border-neutral-200 p-5">
              {replyingTo && (
                <div className="mb-3 p-3 bg-neutral-50 rounded-lg flex items-center justify-between">
                  <span className="text-sm text-neutral-600">
                    Replying to <span className="font-medium">{replyingTo.authorName}</span>
                  </span>
                  <button
                    onClick={() => setReplyingTo(null)}
                    className="text-neutral-400 hover:text-neutral-600"
                  >
                    ×
                  </button>
                </div>
              )}
              <form onSubmit={handleSubmitReply}>
                <textarea
                  value={replyContent}
                  onChange={(e) => setReplyContent(e.target.value)}
                  placeholder="Write your reply..."
                  rows={3}
                  maxLength={5000}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent transition-all resize-none"
                  required
                />
                <div className="flex items-center justify-between mt-3">
                  <span className="text-xs text-neutral-500">
                    {replyContent.length}/5000
                  </span>
                  <button
                    type="submit"
                    disabled={isSubmitting || !replyContent.trim()}
                    className="px-5 py-2 bg-brand-gold-500 text-white font-semibold rounded-lg hover:bg-brand-gold-600 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center gap-2"
                  >
                    {isSubmitting ? (
                      <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    ) : (
                      <Send className="w-4 h-4" />
                    )}
                    Reply
                  </button>
                </div>
              </form>
            </div>
          )}

          {post.status === 'locked' && (
            <div className="text-center py-6 bg-neutral-100 rounded-xl border border-neutral-200">
              <p className="text-neutral-500">This post is locked. No new replies allowed.</p>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
