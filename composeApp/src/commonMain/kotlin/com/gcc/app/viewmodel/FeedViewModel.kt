package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.FeedComment
import com.gcc.shared.model.FeedItem
import com.gcc.shared.model.FeedItemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FeedUiState(
    val items: List<FeedItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val selectedFilter: FeedItemType? = null,
    val error: String? = null,
    // Comment sheet state
    val selectedItemId: String? = null,
    val comments: List<FeedComment> = emptyList(),
    val isLoadingComments: Boolean = false,
    val isPostingComment: Boolean = false,
)

class FeedViewModel(private val apiClient: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()
    private val pageSize = 30

    fun loadFeed() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val typeParam = _uiState.value.selectedFilter?.name?.lowercase()
                val items = apiClient.getFeed(limit = pageSize, offset = 0, type = typeParam)
                _uiState.value = _uiState.value.copy(
                    items = items,
                    isLoading = false,
                    hasMore = items.size >= pageSize,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load feed",
                )
            }
        }
    }

    fun refresh() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            try {
                val typeParam = _uiState.value.selectedFilter?.name?.lowercase()
                val items = apiClient.getFeed(limit = pageSize, offset = 0, type = typeParam)
                _uiState.value = _uiState.value.copy(
                    items = items,
                    isRefreshing = false,
                    hasMore = items.size >= pageSize,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = e.message ?: "Failed to refresh feed",
                )
            }
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                val typeParam = _uiState.value.selectedFilter?.name?.lowercase()
                val newItems = apiClient.getFeed(
                    limit = pageSize,
                    offset = _uiState.value.items.size,
                    type = typeParam,
                )
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items + newItems,
                    isLoadingMore = false,
                    hasMore = newItems.size >= pageSize,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMore = false)
            }
        }
    }

    fun setFilter(type: FeedItemType?) {
        _uiState.value = _uiState.value.copy(selectedFilter = type)
        loadFeed()
    }

    // ── Comments ──────────────────────────────────────────────────────

    fun openComments(feedItemId: String) {
        _uiState.value = _uiState.value.copy(
            selectedItemId = feedItemId,
            comments = emptyList(),
            isLoadingComments = true,
        )
        scope.launch {
            try {
                val comments = apiClient.getFeedComments(feedItemId)
                _uiState.value = _uiState.value.copy(
                    comments = comments,
                    isLoadingComments = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingComments = false)
            }
        }
    }

    fun closeComments() {
        _uiState.value = _uiState.value.copy(
            selectedItemId = null,
            comments = emptyList(),
        )
    }

    fun postComment(text: String) {
        val itemId = _uiState.value.selectedItemId ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isPostingComment = true)
            try {
                val newComment = apiClient.addFeedComment(itemId, text)
                _uiState.value = _uiState.value.copy(
                    comments = _uiState.value.comments + newComment,
                    isPostingComment = false,
                )
                // Update comment count on the feed item
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items.map {
                        if (it.id == itemId) it.copy(commentCount = it.commentCount + 1)
                        else it
                    },
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isPostingComment = false)
            }
        }
    }

    // ── Profile Opt-in ────────────────────────────────────────────────

    fun optInProfile(feedItemId: String) {
        scope.launch {
            try {
                val updated = apiClient.optInFeedProfile(feedItemId)
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items.map {
                        if (it.id == feedItemId) it.copy(actorProfileVisible = true)
                        else it
                    },
                )
            } catch (_: Exception) { }
        }
    }
}
