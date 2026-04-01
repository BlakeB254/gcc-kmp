'use client'

import { useEffect, useState, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import {
  MessageSquare,
  Plus,
  Heart,
  Eye,
  Clock,
  ChevronRight,
  Search,
  Filter,
  Megaphone,
  Pin,
  Users,
  Lightbulb,
  Newspaper,
  HelpCircle,
  RefreshCw,
  X,
  Send,
  ArrowLeft,
} from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'

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
}

interface Pagination {
  page: number
  limit: number
  total: number
  totalPages: number
}

const categoryConfig: Record<string, { label: string; icon: any; color: string; description: string }> = {
  all: { label: 'All Posts', icon: MessageSquare, color: 'bg-neutral-100 text-neutral-700', description: 'Browse all discussions' },
  general: { label: 'General', icon: MessageSquare, color: 'bg-blue-100 text-blue-700', description: 'General community chat' },
  worker_tips: { label: 'Worker Tips', icon: Lightbulb, color: 'bg-amber-100 text-amber-700', description: 'Share cleaning tips & strategies' },
  sponsor_stories: { label: 'Sponsor Stories', icon: Heart, color: 'bg-pink-100 text-pink-700', description: 'Share why you sponsor' },
  feature_requests: { label: 'Feature Requests', icon: HelpCircle, color: 'bg-purple-100 text-purple-700', description: 'Suggest improvements' },
  local_news: { label: 'Local News', icon: Newspaper, color: 'bg-green-100 text-green-700', description: 'Neighborhood updates' },
  announcements: { label: 'Announcements', icon: Megaphone, color: 'bg-red-100 text-red-700', description: 'Official GCC announcements' },
}

export default function CommunityPage() {
  const router = useRouter()
  const [posts, setPosts] = useState<Post[]>([])
  const [pagination, setPagination] = useState<Pagination | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedCategory, setSelectedCategory] = useState('all')
  const [showNewPostModal, setShowNewPostModal] = useState(false)
  const [newPost, setNewPost] = useState({ category: 'general', title: '', content: '' })
  const [isSubmitting, setIsSubmitting] = useState(false)

  const fetchPosts = useCallback(async (category: string = 'all', page: number = 1) => {
    setIsLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams({ page: page.toString(), limit: '20' })
      if (category !== 'all') params.set('category', category)

      const res = await fetch(`/api/forum/posts?${params}`)
      const data = await res.json()

      if (!res.ok) {
        if (res.status === 401) {
          router.push('/worker/login')
          return
        }
        throw new Error(data.error || 'Failed to load posts')
      }

      setPosts(data.posts)
      setPagination(data.pagination)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load posts')
    } finally {
      setIsLoading(false)
    }
  }, [router])

  useEffect(() => {
    fetchPosts(selectedCategory)
  }, [selectedCategory, fetchPosts])

  const handleCreatePost = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!newPost.title.trim() || !newPost.content.trim()) return

    setIsSubmitting(true)
    try {
      const res = await fetch('/api/forum/posts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newPost),
      })
      const data = await res.json()

      if (!res.ok) throw new Error(data.error || 'Failed to create post')

      setShowNewPostModal(false)
      setNewPost({ category: 'general', title: '', content: '' })
      fetchPosts(selectedCategory)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create post')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50">
      {/* Header */}
      <header className="bg-white border-b border-neutral-200 sticky top-0 z-40">
        <div className="section-container py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link href="/" className="flex items-center gap-2">
                <Logo variant="short" size="sm" />
              </Link>
              <div className="hidden sm:block h-6 w-px bg-neutral-200" />
              <h1 className="hidden sm:block text-lg font-semibold text-neutral-900">
                Community Forum
              </h1>
            </div>

            <div className="flex items-center gap-3">
              <button
                onClick={() => fetchPosts(selectedCategory)}
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
                title="Refresh"
              >
                <RefreshCw className="w-5 h-5" />
              </button>
              <button
                onClick={() => setShowNewPostModal(true)}
                className="btn-primary flex items-center gap-2 py-2 px-4 text-sm"
              >
                <Plus className="w-4 h-4" />
                <span className="hidden sm:inline">New Post</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      <main className="section-container py-8">
        <div className="max-w-5xl mx-auto">
          {/* Category Tabs */}
          <div className="mb-8 overflow-x-auto">
            <div className="flex gap-2 pb-2">
              {Object.entries(categoryConfig).map(([key, config]) => {
                const Icon = config.icon
                const isSelected = selectedCategory === key
                return (
                  <button
                    key={key}
                    onClick={() => setSelectedCategory(key)}
                    className={`flex items-center gap-2 px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap transition-all ${
                      isSelected
                        ? 'bg-brand-green-500 text-white shadow-md'
                        : 'bg-white text-neutral-600 hover:bg-neutral-100 border border-neutral-200'
                    }`}
                  >
                    <Icon className="w-4 h-4" />
                    {config.label}
                  </button>
                )
              })}
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
              <p className="text-neutral-500">Loading posts...</p>
            </div>
          ) : posts.length === 0 ? (
            <div className="text-center py-16 bg-white rounded-2xl border border-neutral-200">
              <MessageSquare className="w-12 h-12 text-neutral-300 mx-auto mb-4" />
              <h3 className="text-lg font-semibold text-neutral-900 mb-2">No posts yet</h3>
              <p className="text-neutral-500 mb-6">Be the first to start a discussion!</p>
              <button
                onClick={() => setShowNewPostModal(true)}
                className="btn-primary inline-flex items-center gap-2"
              >
                <Plus className="w-4 h-4" />
                Create Post
              </button>
            </div>
          ) : (
            <div className="space-y-4">
              {posts.map((post) => {
                const catConfig = categoryConfig[post.category] || categoryConfig.general
                return (
                  <Link
                    key={post.id}
                    href={`/community/post/${post.id}`}
                    className="block bg-white rounded-xl border border-neutral-200 p-5 hover:shadow-lg hover:border-brand-green-200 transition-all group"
                  >
                    <div className="flex items-start gap-4">
                      <div className="flex-1 min-w-0">
                        {/* Badges */}
                        <div className="flex flex-wrap items-center gap-2 mb-2">
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
                          <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${catConfig.color}`}>
                            {catConfig.label}
                          </span>
                        </div>

                        {/* Title */}
                        <h3 className="text-lg font-semibold text-neutral-900 mb-1 group-hover:text-brand-green-600 transition-colors line-clamp-2">
                          {post.title}
                        </h3>

                        {/* Preview */}
                        <p className="text-neutral-600 text-sm mb-3 line-clamp-2">
                          {post.content}
                        </p>

                        {/* Meta */}
                        <div className="flex flex-wrap items-center gap-4 text-xs text-neutral-500">
                          <span className="flex items-center gap-1">
                            <Users className="w-3.5 h-3.5" />
                            {post.authorName}
                            <span className={`ml-1 px-1.5 py-0.5 rounded text-[10px] ${
                              post.authorType === 'worker' ? 'bg-brand-green-100 text-brand-green-700' : 'bg-brand-green-100 text-brand-green-700'
                            }`}>
                              {post.authorType}
                            </span>
                          </span>
                          <span className="flex items-center gap-1">
                            <Clock className="w-3.5 h-3.5" />
                            {formatDistanceToNow(new Date(post.createdAt), { addSuffix: true })}
                          </span>
                        </div>
                      </div>

                      {/* Stats */}
                      <div className="flex flex-col items-end gap-2 text-sm text-neutral-500">
                        <div className="flex items-center gap-1">
                          <Eye className="w-4 h-4" />
                          {post.viewCount}
                        </div>
                        <div className="flex items-center gap-1">
                          <MessageSquare className="w-4 h-4" />
                          {post.replyCount}
                        </div>
                        <div className="flex items-center gap-1">
                          <Heart className="w-4 h-4" />
                          {post.likeCount}
                        </div>
                      </div>

                      <ChevronRight className="w-5 h-5 text-neutral-400 group-hover:text-brand-green-500 transition-colors" />
                    </div>
                  </Link>
                )
              })}
            </div>
          )}

          {/* Pagination */}
          {pagination && pagination.totalPages > 1 && (
            <div className="mt-8 flex justify-center gap-2">
              {Array.from({ length: pagination.totalPages }, (_, i) => i + 1).map((page) => (
                <button
                  key={page}
                  onClick={() => fetchPosts(selectedCategory, page)}
                  className={`w-10 h-10 rounded-lg font-medium transition-colors ${
                    page === pagination.page
                      ? 'bg-brand-green-500 text-white'
                      : 'bg-white text-neutral-600 hover:bg-neutral-100 border border-neutral-200'
                  }`}
                >
                  {page}
                </button>
              ))}
            </div>
          )}
        </div>
      </main>

      {/* New Post Modal */}
      {showNewPostModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setShowNewPostModal(false)} />
          <div className="relative bg-white rounded-2xl shadow-xl max-w-lg w-full max-h-[90vh] overflow-y-auto">
            <div className="sticky top-0 bg-white border-b border-neutral-200 px-6 py-4 flex items-center justify-between rounded-t-2xl">
              <h2 className="text-xl font-bold text-neutral-900">Create Post</h2>
              <button
                onClick={() => setShowNewPostModal(false)}
                className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleCreatePost} className="p-6 space-y-5">
              {/* Category */}
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  Category
                </label>
                <select
                  value={newPost.category}
                  onChange={(e) => setNewPost({ ...newPost, category: e.target.value })}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-green-500 focus:border-transparent transition-all"
                >
                  {Object.entries(categoryConfig)
                    .filter(([key]) => key !== 'all' && key !== 'announcements')
                    .map(([key, config]) => (
                      <option key={key} value={key}>
                        {config.label}
                      </option>
                    ))}
                </select>
              </div>

              {/* Title */}
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  Title
                </label>
                <input
                  type="text"
                  value={newPost.title}
                  onChange={(e) => setNewPost({ ...newPost, title: e.target.value })}
                  placeholder="What's on your mind?"
                  maxLength={255}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-green-500 focus:border-transparent transition-all"
                  required
                />
                <p className="mt-1 text-xs text-neutral-500 text-right">
                  {newPost.title.length}/255
                </p>
              </div>

              {/* Content */}
              <div>
                <label className="block text-sm font-medium text-neutral-700 mb-2">
                  Content
                </label>
                <textarea
                  value={newPost.content}
                  onChange={(e) => setNewPost({ ...newPost, content: e.target.value })}
                  placeholder="Share your thoughts, tips, or questions..."
                  rows={6}
                  maxLength={10000}
                  className="w-full px-4 py-3 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-brand-green-500 focus:border-transparent transition-all resize-none"
                  required
                />
                <p className="mt-1 text-xs text-neutral-500 text-right">
                  {newPost.content.length}/10000
                </p>
              </div>

              {/* Submit */}
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={() => setShowNewPostModal(false)}
                  className="flex-1 py-3 px-4 border border-neutral-300 text-neutral-700 font-medium rounded-xl hover:bg-neutral-50 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting || !newPost.title.trim() || !newPost.content.trim()}
                  className="flex-1 py-3 px-4 bg-brand-green-500 text-white font-semibold rounded-xl hover:bg-brand-green-600 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center justify-center gap-2"
                >
                  {isSubmitting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      Posting...
                    </>
                  ) : (
                    <>
                      <Send className="w-4 h-4" />
                      Post
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
