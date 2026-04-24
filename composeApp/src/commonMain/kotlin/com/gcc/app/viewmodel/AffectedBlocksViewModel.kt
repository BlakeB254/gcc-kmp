package com.gcc.app.viewmodel

import com.gcc.shared.api.AffectedAreasRepository
import com.gcc.shared.model.AffectedBlock
import com.gcc.shared.model.AffectedBlockStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AffectedBlocksUiState(
    val blocks: List<AffectedBlock> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedBlock: AffectedBlock? = null,
)

/**
 * Drives the dynamic heatmap shown in MapFullScreen.
 *
 * Starts a polling loop when `startPolling()` is called (typically on
 * entering the map screen) and stops it on `stopPolling()`. Polling
 * keeps the heatmap reactive to newly scheduled cleanings and live
 * cleaning-in-progress transitions without the user needing to refresh.
 */
class AffectedBlocksViewModel(
    private val repo: AffectedAreasRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val _uiState = MutableStateFlow(AffectedBlocksUiState())
    val uiState: StateFlow<AffectedBlocksUiState> = _uiState.asStateFlow()

    /** Start fetching and polling on the default 30s cadence. Idempotent. */
    fun startPolling(
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
        intervalMs: Long = 30_000L,
    ) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // Immediate first fetch for instant paint
            refreshOnce(serviceAreaId, minStatus)
            // Then poll
            kotlinx.coroutines.delay(intervalMs)
            while (true) {
                refreshOnce(serviceAreaId, minStatus)
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refreshOnce(
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ) {
        _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
        try {
            val fresh = repo.fetch(serviceAreaId, minStatus)
            _uiState.value = _uiState.value.copy(
                blocks = fresh,
                isRefreshing = false,
                error = null,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                error = e.message ?: "Unknown error",
            )
        }
    }

    fun selectBlock(blockId: Int?) {
        val next = blockId?.let { id -> _uiState.value.blocks.firstOrNull { it.blockId == id } }
        _uiState.value = _uiState.value.copy(selectedBlock = next)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedBlock = null)
    }
}
