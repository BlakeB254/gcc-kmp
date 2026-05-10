package com.gcc.app.viewmodel

import com.gcc.app.platform.openUrl
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WalletUiState(
    val wallet: SponsorWallet? = null,
    val transactions: List<WalletTransaction> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val depositAmount: String = "",
    val isDepositing: Boolean = false,
    val depositError: String? = null,
    val isAllocating: Boolean = false,
    val allocateError: String? = null,
    // Poster identity (shared across flows)
    val isAnonymous: Boolean = false,
    val posterName: String = "",
    val posterEmail: String = "",
    // Block cleaning flow
    val showBlockSheet: Boolean = false,
    val availableBlocks: List<Block> = emptyList(),
    val selectedBlock: Block? = null,
    val blockDate: String = "",
    val blockTime: String = "",
    val isLoadingBlocks: Boolean = false,
    val isSponsoringBlock: Boolean = false,
    val blockError: String? = null,
    val blockSuccess: Boolean = false,
    // Bounty posting flow
    val showBountySheet: Boolean = false,
    val bountyTitle: String = "",
    val bountyDescription: String = "",
    val bountyAmount: String = "",
    val bountyVisibility: BountyVisibility = BountyVisibility.PUBLIC,
    val bountySpotMode: BountySpotMode = BountySpotMode.EXISTING,
    val nearbySpots: List<CommunityReport> = emptyList(),
    val selectedSpot: CommunityReport? = null,
    val isLoadingSpots: Boolean = false,
    val isPostingBounty: Boolean = false,
    val bountyError: String? = null,
    val bountySuccess: Boolean = false,
)

enum class BountySpotMode { EXISTING, NEW }

class WalletViewModel(
    private val apiClient: GccApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    fun loadWallet(silent: Boolean = false) {
        scope.launch {
            if (!silent) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val wallet = apiClient.getWallet()
                _uiState.value = _uiState.value.copy(
                    wallet = wallet,
                    isLoading = false,
                )
            } catch (e: Exception) {
                if (!silent) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load wallet",
                    )
                }
            }
        }
    }

    fun deposit(amountCents: Int, fundType: String = "general") {
        scope.launch {
            _uiState.value = _uiState.value.copy(isDepositing = true, depositError = null)
            try {
                val wallet = apiClient.depositToWallet(amountCents, fundType)
                _uiState.value = _uiState.value.copy(
                    wallet = wallet,
                    isDepositing = false,
                    depositAmount = "",
                )
                loadTransactions()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDepositing = false,
                    depositError = e.message ?: "Failed to deposit",
                )
            }
        }
    }

    fun allocateFunds(general: Int, route: Int, bounty: Int) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isAllocating = true, allocateError = null)
            try {
                val wallet = apiClient.allocateWalletFunds(general, route, bounty)
                _uiState.value = _uiState.value.copy(
                    wallet = wallet,
                    isAllocating = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAllocating = false,
                    allocateError = e.message ?: "Failed to allocate funds",
                )
            }
        }
    }

    fun loadTransactions(limit: Int = 50, offset: Int = 0) {
        scope.launch {
            try {
                val transactions = apiClient.getWalletTransactions(limit, offset)
                _uiState.value = _uiState.value.copy(transactions = transactions)
            } catch (_: Exception) {
                // Transaction loading is non-critical; silently fail
            }
        }
    }

    fun updateDepositAmount(amount: String) {
        _uiState.value = _uiState.value.copy(depositAmount = amount)
    }

    // ── Poster Identity ──────────────────────────────────────────────

    fun setAnonymous(anonymous: Boolean) {
        _uiState.value = _uiState.value.copy(isAnonymous = anonymous)
    }

    fun updatePosterName(name: String) {
        _uiState.value = _uiState.value.copy(posterName = name)
    }

    fun updatePosterEmail(email: String) {
        _uiState.value = _uiState.value.copy(posterEmail = email)
    }

    // ── Block Cleaning Flow ───────────────────────────────────────────

    fun openBlockSheet() {
        _uiState.value = _uiState.value.copy(
            showBlockSheet = true,
            selectedBlock = null,
            blockDate = "",
            blockTime = "",
            blockError = null,
            blockSuccess = false,
            isAnonymous = false,
            posterName = "",
            posterEmail = "",
        )
        loadAvailableBlocks()
    }

    fun dismissBlockSheet() {
        _uiState.value = _uiState.value.copy(showBlockSheet = false)
    }

    private fun loadAvailableBlocks() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoadingBlocks = true)
            try {
                val blocks = apiClient.getAvailableBlocks()
                _uiState.value = _uiState.value.copy(
                    availableBlocks = blocks,
                    isLoadingBlocks = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingBlocks = false,
                    blockError = e.message ?: "Failed to load blocks",
                )
            }
        }
    }

    fun selectBlock(block: Block) {
        _uiState.value = _uiState.value.copy(selectedBlock = block, blockError = null)
    }

    fun updateBlockDate(date: String) {
        _uiState.value = _uiState.value.copy(blockDate = date, blockError = null)
    }

    fun updateBlockTime(time: String) {
        _uiState.value = _uiState.value.copy(blockTime = time, blockError = null)
    }

    fun confirmBlockSponsorship() {
        val state = _uiState.value
        val block = state.selectedBlock
        if (block == null) {
            _uiState.value = state.copy(blockError = "Please select a block")
            return
        }
        if (state.blockDate.isBlank()) {
            _uiState.value = state.copy(blockError = "Please select a date")
            return
        }
        if (state.blockTime.isBlank()) {
            _uiState.value = state.copy(blockError = "Please select a preferred time")
            return
        }
        if (!state.isAnonymous && state.posterName.isBlank()) {
            _uiState.value = state.copy(blockError = "Please enter your name or choose anonymous")
            return
        }
        if (state.posterEmail.isBlank()) {
            _uiState.value = state.copy(blockError = "Email is required for payment receipt")
            return
        }

        // Parse preferred time to start/end window
        val (timeStart, timeEnd) = parseTimeWindow(state.blockTime)

        scope.launch {
            _uiState.value = _uiState.value.copy(isSponsoringBlock = true, blockError = null)
            try {
                val checkout = apiClient.createCheckoutSession(
                    CheckoutRequest(
                        blockIds = listOf(block.id),
                        frequency = "one_time",
                        allocationType = "block",
                        name = if (state.isAnonymous) "Anonymous Sponsor" else state.posterName,
                        email = state.posterEmail,
                        displayName = if (state.isAnonymous) null else state.posterName,
                        isAnonymous = state.isAnonymous,
                        preferredCleaningDate = state.blockDate,
                        preferredCleaningTimeStart = timeStart,
                        preferredCleaningTimeEnd = timeEnd,
                    ),
                )
                _uiState.value = _uiState.value.copy(isSponsoringBlock = false)
                openUrl(checkout.checkoutUrl)
                _uiState.value = _uiState.value.copy(blockSuccess = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSponsoringBlock = false,
                    blockError = e.message ?: "Failed to create checkout",
                )
            }
        }
    }

    // ── Bounty Posting Flow ───────────────────────────────────────────

    fun openBountySheet() {
        _uiState.value = _uiState.value.copy(
            showBountySheet = true,
            bountyTitle = "",
            bountyDescription = "",
            bountyAmount = "",
            bountyVisibility = BountyVisibility.PUBLIC,
            bountySpotMode = BountySpotMode.EXISTING,
            selectedSpot = null,
            bountyError = null,
            bountySuccess = false,
            isAnonymous = false,
            posterName = "",
            posterEmail = "",
        )
        loadNearbySpots()
    }

    fun dismissBountySheet() {
        _uiState.value = _uiState.value.copy(showBountySheet = false)
    }

    private fun loadNearbySpots() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSpots = true)
            try {
                val spots = apiClient.getSpots(limit = 30)
                _uiState.value = _uiState.value.copy(
                    nearbySpots = spots,
                    isLoadingSpots = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingSpots = false,
                    bountyError = e.message ?: "Failed to load spots",
                )
            }
        }
    }

    fun updateBountyTitle(title: String) {
        _uiState.value = _uiState.value.copy(bountyTitle = title, bountyError = null)
    }

    fun updateBountyDescription(desc: String) {
        _uiState.value = _uiState.value.copy(bountyDescription = desc, bountyError = null)
    }

    fun updateBountyAmount(amount: String) {
        _uiState.value = _uiState.value.copy(bountyAmount = amount, bountyError = null)
    }

    fun setBountyVisibility(visibility: BountyVisibility) {
        _uiState.value = _uiState.value.copy(bountyVisibility = visibility)
    }

    fun setBountySpotMode(mode: BountySpotMode) {
        _uiState.value = _uiState.value.copy(bountySpotMode = mode, selectedSpot = null, bountyError = null)
    }

    fun selectSpot(spot: CommunityReport) {
        _uiState.value = _uiState.value.copy(selectedSpot = spot, bountyError = null)
    }

    fun confirmPostBounty() {
        val state = _uiState.value
        if (state.bountyTitle.isBlank()) {
            _uiState.value = state.copy(bountyError = "Please enter a bounty title")
            return
        }
        val amountDollars = state.bountyAmount.toIntOrNull()
        if (amountDollars == null || amountDollars < 10) {
            _uiState.value = state.copy(bountyError = "Minimum bounty amount is \$10")
            return
        }
        if (state.selectedSpot == null) {
            _uiState.value = state.copy(bountyError = if (state.bountySpotMode == BountySpotMode.NEW) "Please report a spot first using Spot Report" else "Please select an existing spot")
            return
        }
        if (!state.isAnonymous && state.posterName.isBlank()) {
            _uiState.value = state.copy(bountyError = "Please enter your name or choose anonymous")
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(isPostingBounty = true, bountyError = null)
            try {
                val spot = state.selectedSpot
                val request = CreateBountyRequest(
                    title = state.bountyTitle,
                    description = state.bountyDescription.ifBlank { null },
                    amount = amountDollars * 100,
                    locationLat = spot?.coordinates?.lat ?: 41.8604,
                    locationLng = spot?.coordinates?.lng ?: -87.7228,
                    communityArea = spot?.let { "North Lawndale" },
                    mediaUrls = spot?.mediaUrls ?: emptyList(),
                    triggerReportId = spot?.id,
                    visibility = state.bountyVisibility,
                    isAnonymous = state.isAnonymous,
                    posterName = if (state.isAnonymous) null else state.posterName,
                )
                apiClient.createBounty(request)
                _uiState.value = _uiState.value.copy(
                    isPostingBounty = false,
                    bountySuccess = true,
                )
                loadWallet(silent = true)
                loadTransactions()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isPostingBounty = false,
                    bountyError = e.message ?: "Failed to post bounty",
                )
            }
        }
    }

    private fun parseTimeWindow(timeLabel: String): Pair<String, String> = when {
        "Morning" in timeLabel -> "07:00" to "10:00"
        "Midday" in timeLabel -> "10:00" to "13:00"
        "Afternoon" in timeLabel -> "13:00" to "16:00"
        "Evening" in timeLabel -> "16:00" to "19:00"
        else -> "07:00" to "19:00"
    }
}
