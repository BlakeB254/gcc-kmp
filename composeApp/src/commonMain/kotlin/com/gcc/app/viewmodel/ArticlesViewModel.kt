package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Article
import com.gcc.shared.model.ArticleSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ArticlesUiState(
    val articles: List<ArticleSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Currently-open article in the reader. Null when reader is closed. */
    val openArticle: Article? = null,
    val isLoadingArticle: Boolean = false,
    val articleError: String? = null,
)

/**
 * Drives the Articles tab and reader. Lazily fetches the full article
 * body only when the user opens one, keeping the list-view payload light.
 */
class ArticlesViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ArticlesUiState())
    val uiState: StateFlow<ArticlesUiState> = _uiState.asStateFlow()

    fun loadArticles() {
        if (_uiState.value.isLoading) return
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val list = apiClient.getArticles()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    articles = list,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load articles",
                )
            }
        }
    }

    fun openArticle(slug: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingArticle = true,
                articleError = null,
                openArticle = null,
            )
            try {
                val article = apiClient.getArticle(slug)
                _uiState.value = _uiState.value.copy(
                    isLoadingArticle = false,
                    openArticle = article,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingArticle = false,
                    articleError = e.message ?: "Failed to load article",
                )
            }
        }
    }

    fun closeArticle() {
        _uiState.value = _uiState.value.copy(
            openArticle = null,
            articleError = null,
        )
    }
}
