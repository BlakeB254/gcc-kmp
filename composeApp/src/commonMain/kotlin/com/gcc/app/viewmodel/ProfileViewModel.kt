package com.gcc.app.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val worker: Worker? = null,
    val badges: List<Badge> = emptyList(),
    val streakDays: Int = 0,
    val isLoading: Boolean = true,
    val newlyUnlockedBadge: Badge? = null,
    val error: String? = null,
)

class ProfileViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun loadProfile() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val workerResponse = apiClient.httpClient.get("${apiClient.baseUrl}/api/workers/me")
                val worker = workerResponse.body<ApiResponse<Worker>>()

                // Load badges — the server may not have this endpoint yet,
                // so we generate placeholder badges from worker stats
                val badges = generateBadgesFromStats(worker.data)

                _uiState.value = _uiState.value.copy(
                    worker = worker.data,
                    badges = badges,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load profile",
                )
            }
        }
    }

    fun dismissUnlockedBadge() {
        _uiState.value = _uiState.value.copy(newlyUnlockedBadge = null)
    }

    private fun generateBadgesFromStats(worker: Worker): List<Badge> {
        val stats = worker.stats
        return listOf(
            Badge(
                id = "jobs_1",
                category = BadgeCategory.JOBS,
                name = "First Clean",
                description = "Complete your first cleaning job",
                tier = BadgeTier.BRONZE,
                requirement = 1,
                progress = stats.jobsCompleted,
                isUnlocked = stats.jobsCompleted >= 1,
            ),
            Badge(
                id = "jobs_10",
                category = BadgeCategory.JOBS,
                name = "First Ten",
                description = "Complete 10 cleaning jobs",
                tier = BadgeTier.SILVER,
                requirement = 10,
                progress = stats.jobsCompleted,
                isUnlocked = stats.jobsCompleted >= 10,
            ),
            Badge(
                id = "jobs_50",
                category = BadgeCategory.JOBS,
                name = "Fifty Strong",
                description = "Complete 50 cleaning jobs",
                tier = BadgeTier.GOLD,
                requirement = 50,
                progress = stats.jobsCompleted,
                isUnlocked = stats.jobsCompleted >= 50,
            ),
            Badge(
                id = "jobs_100",
                category = BadgeCategory.JOBS,
                name = "Century Club",
                description = "Complete 100 cleaning jobs",
                tier = BadgeTier.PLATINUM,
                requirement = 100,
                progress = stats.jobsCompleted,
                isUnlocked = stats.jobsCompleted >= 100,
            ),
            Badge(
                id = "earnings_1000",
                category = BadgeCategory.EARNINGS,
                name = "First Grand",
                description = "Earn $1,000 total",
                tier = BadgeTier.SILVER,
                requirement = 100000, // cents
                progress = stats.totalEarnings,
                isUnlocked = stats.totalEarnings >= 100000,
            ),
            Badge(
                id = "earnings_5000",
                category = BadgeCategory.EARNINGS,
                name = "Five Stacks",
                description = "Earn $5,000 total",
                tier = BadgeTier.GOLD,
                requirement = 500000,
                progress = stats.totalEarnings,
                isUnlocked = stats.totalEarnings >= 500000,
            ),
            Badge(
                id = "founder",
                category = BadgeCategory.EARLY_ADOPTER,
                name = "Founder",
                description = "One of the first workers on the platform",
                tier = BadgeTier.DIAMOND,
                requirement = 1,
                progress = if (worker.earlyAdopterTier == EarlyAdopterTier.FOUNDER) 1 else 0,
                isUnlocked = worker.earlyAdopterTier == EarlyAdopterTier.FOUNDER,
            ),
            Badge(
                id = "pioneer",
                category = BadgeCategory.EARLY_ADOPTER,
                name = "Pioneer",
                description = "Joined during the pilot program",
                tier = BadgeTier.PLATINUM,
                requirement = 1,
                progress = if (worker.earlyAdopterTier.ordinal <= EarlyAdopterTier.PIONEER.ordinal) 1 else 0,
                isUnlocked = worker.earlyAdopterTier.ordinal <= EarlyAdopterTier.PIONEER.ordinal,
            ),
        )
    }
}

