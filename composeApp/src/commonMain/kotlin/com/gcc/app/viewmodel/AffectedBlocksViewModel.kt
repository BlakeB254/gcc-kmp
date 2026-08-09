package com.gcc.app.viewmodel

import com.gcc.app.polling.AppVisibility
import com.gcc.app.polling.PollingGate
import com.gcc.shared.api.AffectedAreasRepository
import com.gcc.shared.model.AffectedBlock
import com.gcc.shared.model.AffectedBlockStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AffectedBlocksUiState(
    val blocks: List<AffectedBlock> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedBlock: AffectedBlock? = null,
)

/**
 * Drives the dynamic heatmap shown in MapFullScreen.
 *
 * Starts a single polling job when `startPolling()` is called and stops it on
 * `stopPolling()` / background visibility. [PollingGate] owns the cadence so
 * iOS and Android share identical suspend/backoff behavior.
 */
class AffectedBlocksViewModel(
    private val repo: AffectedAreasRepository,
    private val gate: PollingGate = PollingGate(
        baseIntervalMillis = 25_000L,
        maxIntervalMillis = 120_000L,
    ),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private var pollJob: Job? = null
    private var visibility: AppVisibility = AppVisibility.Background
    private var consecutiveUnchanged: Int = 0
    private var lastFingerprint: String? = null

    private val _uiState = MutableStateFlow(AffectedBlocksUiState())
    val uiState: StateFlow<AffectedBlocksUiState> = _uiState.asStateFlow()

    /**
     * Start fetching and polling on the gated cadence. Idempotent — never
     * stacks a second timer on top of an active job.
     */
    fun startPolling(
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ) {
        visibility = AppVisibility.Foreground
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // Immediate first fetch for instant paint / foreground catch-up.
            refreshOnce(serviceAreaId, minStatus)
            while (true) {
                val delayMs = gate.nextDelayMillis(visibility, consecutiveUnchanged)
                if (delayMs == null) {
                    // Backgrounded: wait briefly and re-check without hitting
                    // the network so a later foreground transition can resume.
                    delay(1_000L)
                    continue
                }
                delay(delayMs)
                if (visibility != AppVisibility.Foreground) continue
                refreshOnce(serviceAreaId, minStatus)
            }
        }
    }

    fun stopPolling() {
        visibility = AppVisibility.Background
        pollJob?.cancel()
        pollJob = null
        consecutiveUnchanged = 0
    }

    /**
     * Lifecycle hook for app-level foreground/background while the map screen
     * remains composed. Foreground transitions issue one catch-up refresh and
     * reset backoff; background suppresses scheduled polls without cancelling
     * the job so resume stays immediate.
     */
    fun onVisibilityChanged(
        next: AppVisibility,
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ) {
        val previous = visibility
        visibility = next
        if (next == AppVisibility.Foreground && previous != AppVisibility.Foreground) {
            consecutiveUnchanged = 0
            if (pollJob?.isActive == true) {
                scope.launch { refreshOnce(serviceAreaId, minStatus) }
            } else {
                startPolling(serviceAreaId, minStatus)
            }
        }
    }

    suspend fun refreshOnce(
        serviceAreaId: Int? = null,
        minStatus: AffectedBlockStatus = AffectedBlockStatus.AVAILABLE,
    ) {
        // Serialize manual + scheduled refresh so stop/start cannot duplicate
        // in-flight HTTP work against the same repository.
        refreshMutex.withLock {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            try {
                val fresh = repo.fetch(serviceAreaId, minStatus)
                val fingerprint = fresh.joinToString("|") { "${it.blockId}:${it.status}:${it.intensity}" }
                consecutiveUnchanged = if (fingerprint == lastFingerprint && lastFingerprint != null) {
                    consecutiveUnchanged + 1
                } else {
                    0
                }
                lastFingerprint = fingerprint
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
    }

    fun selectBlock(blockId: Int?) {
        val next = blockId?.let { id -> _uiState.value.blocks.firstOrNull { it.blockId == id } }
        _uiState.value = _uiState.value.copy(selectedBlock = next)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedBlock = null)
    }
}
