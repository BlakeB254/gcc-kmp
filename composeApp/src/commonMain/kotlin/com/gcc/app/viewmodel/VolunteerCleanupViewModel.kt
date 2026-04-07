package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VolunteerPhase {
    BEFORE_PHOTOS,
    IN_PROGRESS,
    AFTER_PHOTOS,
    TRASH_HEAP_PHOTO,
    SUBMITTING,
    COMPLETE,
    ALREADY_CLEAN_COMPLETE,
}

data class VolunteerCleanupUiState(
    val reportId: Int = 0,
    val cleanup: VolunteerCleanup? = null,
    val elapsedSeconds: Long = 0,
    val beforePhotoUrls: List<String> = emptyList(),
    val afterPhotoUrls: List<String> = emptyList(),
    val trashHeapUrl: String? = null,
    val isSubmitting: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null,
    val phase: VolunteerPhase = VolunteerPhase.BEFORE_PHOTOS,
    val pointsAwarded: Int = 0,
    val spotLocation: Coordinates? = null,
)

class VolunteerCleanupViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private val _uiState = MutableStateFlow(VolunteerCleanupUiState())
    val uiState: StateFlow<VolunteerCleanupUiState> = _uiState.asStateFlow()

    private var beforeScanData: ScanCapturedPhoto? = null
    private var afterScanData: ScanCapturedPhoto? = null

    fun initForReport(reportId: Int) {
        timerJob?.cancel()
        beforeScanData = null
        afterScanData = null
        _uiState.value = VolunteerCleanupUiState(
            reportId = reportId,
            phase = VolunteerPhase.BEFORE_PHOTOS,
        )
        scope.launch {
            try {
                val report = apiClient.getSpot(reportId)
                _uiState.value = _uiState.value.copy(spotLocation = report.coordinates)
            } catch (_: Exception) {
                // spotLocation stays null — fallback placeholder will be shown
            }
        }
    }

    fun addBeforePhoto(url: String) {
        _uiState.value = _uiState.value.copy(
            beforePhotoUrls = _uiState.value.beforePhotoUrls + url,
        )
    }

    fun addBeforeScanPhoto(scanPhoto: ScanCapturedPhoto) {
        beforeScanData = scanPhoto
        addBeforePhoto("before_${_uiState.value.beforePhotoUrls.size + 1}.jpg")
    }

    fun addAfterScanPhoto(scanPhoto: ScanCapturedPhoto) {
        afterScanData = scanPhoto
        addAfterPhoto("after_${_uiState.value.afterPhotoUrls.size + 1}.jpg")
    }

    fun setTrashHeapScanPhoto(scanPhoto: ScanCapturedPhoto) {
        setTrashHeapUrl("trash_heap.jpg")
    }

    fun uploadBeforePhotos(photoBytes: List<ByteArray>) {
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
                val urls = apiClient.uploadSpotPhotos(photoBytes)
                _uiState.value = _uiState.value.copy(
                    beforePhotoUrls = _uiState.value.beforePhotoUrls + urls,
                    isSubmitting = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = "Failed to upload photos: ${e.message}",
                )
            }
        }
    }

    fun startCleanup() {
        val state = _uiState.value
        scope.launch {
            _uiState.value = state.copy(isSubmitting = true, error = null)
            try {
                val request = StartVolunteerRequest(
                    beforeMediaUrls = state.beforePhotoUrls,
                    beforeDepthAnalysis = beforeScanData?.depthAnalysis,
                    beforeAiAnalysis = beforeScanData?.aiAnalysis,
                )
                val cleanup = apiClient.startVolunteerCleanup(state.reportId, request)
                _uiState.value = _uiState.value.copy(
                    cleanup = cleanup,
                    phase = VolunteerPhase.IN_PROGRESS,
                    isSubmitting = false,
                )
                startTimer()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = e.message ?: "Failed to start cleanup",
                )
            }
        }
    }

    fun reportAlreadyClean() {
        val state = _uiState.value
        scope.launch {
            _uiState.value = state.copy(
                isSubmitting = true,
                error = null,
                phase = VolunteerPhase.SUBMITTING,
            )
            try {
                val cleanup = apiClient.reportSpotAlreadyClean(
                    state.reportId,
                    state.beforePhotoUrls,
                )
                _uiState.value = _uiState.value.copy(
                    cleanup = cleanup,
                    isSubmitting = false,
                    isCompleted = true,
                    pointsAwarded = cleanup.pointsAwarded,
                    phase = VolunteerPhase.ALREADY_CLEAN_COMPLETE,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = VolunteerPhase.BEFORE_PHOTOS,
                    error = e.message ?: "Failed to report spot as clean",
                )
            }
        }
    }

    fun addAfterPhoto(url: String) {
        _uiState.value = _uiState.value.copy(
            afterPhotoUrls = _uiState.value.afterPhotoUrls + url,
        )
    }

    fun setTrashHeapUrl(url: String) {
        _uiState.value = _uiState.value.copy(trashHeapUrl = url)
    }

    fun advancePhase() {
        val next = when (_uiState.value.phase) {
            VolunteerPhase.BEFORE_PHOTOS -> return // handled by startCleanup/reportAlreadyClean
            VolunteerPhase.IN_PROGRESS -> VolunteerPhase.AFTER_PHOTOS
            VolunteerPhase.AFTER_PHOTOS -> VolunteerPhase.TRASH_HEAP_PHOTO
            VolunteerPhase.TRASH_HEAP_PHOTO -> {
                completeCleanup()
                return
            }
            VolunteerPhase.SUBMITTING -> return
            VolunteerPhase.COMPLETE -> return
            VolunteerPhase.ALREADY_CLEAN_COMPLETE -> return
        }
        _uiState.value = _uiState.value.copy(phase = next, error = null)
    }

    private fun completeCleanup() {
        val state = _uiState.value
        scope.launch {
            _uiState.value = state.copy(
                phase = VolunteerPhase.SUBMITTING,
                isSubmitting = true,
                error = null,
            )
            try {
                val request = CompleteVolunteerRequest(
                    afterMediaUrls = state.afterPhotoUrls,
                    trashHeapMediaUrl = state.trashHeapUrl,
                    afterDepthAnalysis = afterScanData?.depthAnalysis,
                    afterAiAnalysis = afterScanData?.aiAnalysis,
                )
                val cleanup = apiClient.completeVolunteerCleanup(state.reportId, request)
                _uiState.value = _uiState.value.copy(
                    cleanup = cleanup,
                    isSubmitting = false,
                    isCompleted = true,
                    pointsAwarded = cleanup.pointsAwarded,
                    phase = VolunteerPhase.COMPLETE,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    phase = VolunteerPhase.TRASH_HEAP_PHOTO,
                    error = e.message ?: "Failed to complete cleanup",
                )
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_uiState.value.phase == VolunteerPhase.IN_PROGRESS && !_uiState.value.isCompleted) {
                delay(1000L)
                _uiState.value = _uiState.value.copy(
                    elapsedSeconds = _uiState.value.elapsedSeconds + 1,
                )
            }
        }
    }

    fun onCleared() {
        timerJob?.cancel()
    }
}
