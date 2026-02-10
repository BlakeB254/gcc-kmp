package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
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
}
