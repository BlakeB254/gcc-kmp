package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Sponsor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SponsorDisplayTier { GOLD, SILVER, BRONZE }

data class SponsorWallUiState(
    val sponsors: List<Sponsor> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class SponsorWallViewModel(private val apiClient: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SponsorWallUiState())
    val uiState: StateFlow<SponsorWallUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun loadSponsors() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val sponsors = apiClient.getSponsorWall()
                _uiState.value = _uiState.value.copy(sponsors = sponsors, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load sponsors",
                )
            }
        }
    }

    companion object {
        private const val GOLD_THRESHOLD = 100_00   // $100+
        private const val SILVER_THRESHOLD = 50_00   // $50+

        /** Derive a display tier from total donated amount (cents). */
        fun tierFor(totalDonated: Int): SponsorDisplayTier = when {
            totalDonated >= GOLD_THRESHOLD -> SponsorDisplayTier.GOLD
            totalDonated >= SILVER_THRESHOLD -> SponsorDisplayTier.SILVER
            else -> SponsorDisplayTier.BRONZE
        }
    }
}
