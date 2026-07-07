package com.gcc.app.viewmodel

import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.pickFromLibrary
import com.gcc.shared.api.GccApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DocumentUploadUiState(
    val governmentIdPhoto: CapturedPhoto? = null,
    val proofOfAddressPhoto: CapturedPhoto? = null,
    val governmentIdUrl: String? = null,
    val proofOfAddressUrl: String? = null,
    val isUploading: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null,
)

private enum class DocType { GOVERNMENT_ID, PROOF_OF_ADDRESS }

class DocumentUploadViewModel(
    private val apiClient: GccApiClient,
    private val cameraManager: CameraManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(DocumentUploadUiState())
    val uiState: StateFlow<DocumentUploadUiState> = _uiState.asStateFlow()

    fun onCleared() {
        scope.cancel()
    }

    fun captureGovernmentId() = acquirePhoto(DocType.GOVERNMENT_ID) { cameraManager.capturePhoto() }
    fun pickGovernmentIdFromLibrary() = acquirePhoto(DocType.GOVERNMENT_ID) { pickFromLibrary() }
    fun captureProofOfAddress() = acquirePhoto(DocType.PROOF_OF_ADDRESS) { cameraManager.capturePhoto() }
    fun pickProofOfAddressFromLibrary() = acquirePhoto(DocType.PROOF_OF_ADDRESS) { pickFromLibrary() }

    private fun acquirePhoto(type: DocType, provider: suspend () -> CapturedPhoto?) {
        scope.launch {
            try {
                val photo = provider() ?: return@launch
                _uiState.value = when (type) {
                    DocType.GOVERNMENT_ID -> _uiState.value.copy(governmentIdPhoto = photo, error = null)
                    DocType.PROOF_OF_ADDRESS -> _uiState.value.copy(proofOfAddressPhoto = photo, error = null)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to capture photo")
            }
        }
    }

    fun submitDocuments() {
        val govPhoto = _uiState.value.governmentIdPhoto ?: return
        val addressPhoto = _uiState.value.proofOfAddressPhoto ?: return

        scope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            try {
                // Verification documents go to the private R2 bucket via the
                // dedicated endpoint — never the public spot-photo bucket.
                val urls = apiClient.uploadVerificationDocuments(listOf(govPhoto.bytes, addressPhoto.bytes))
                if (urls.size >= 2) {
                    apiClient.submitVerificationDocuments(
                        governmentIdUrl = urls[0],
                        proofOfAddressUrl = urls[1],
                    )
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        isComplete = true,
                        governmentIdUrl = urls[0],
                        proofOfAddressUrl = urls[1],
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        error = "Upload failed — not all photos were saved",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = e.message ?: "Upload failed",
                )
            }
        }
    }
}
