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
    // Worker earnings / Stripe Connect payout status
    val connectStatus: ConnectStatus? = null,
    val connectStatusLoading: Boolean = false,
    val connectOnboardLoading: Boolean = false,
    val connectError: String? = null,
    // How the worker gets paid (stripe vs mailed check) + ad-hoc payout history
    val payoutMethodInfo: PayoutMethodInfo? = null,
    val payoutMethodSaving: Boolean = false,
    val payoutMethodError: String? = null,
    val showCheckAddressForm: Boolean = false,
    val checkAddressName: String = "",
    val checkAddressStreet: String = "",
    val checkAddressCity: String = "",
    val checkAddressState: String = "",
    val checkAddressZip: String = "",
    val myPayouts: List<WorkerPayout> = emptyList(),
    // Worker wallet: in-app earnings balance + cash-out flow
    val workerWallet: WorkerWallet? = null,
    val workerWalletLoading: Boolean = false,
    val showCashOutSheet: Boolean = false,
    val cashOutAmount: String = "",
    val cashOutMethod: String = "stripe", // "stripe" | "check"
    val cashOutInFlight: Boolean = false,
    val cashOutError: String? = null,
    val cashOutSuccess: String? = null,
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

    /** Worker payout readiness (Stripe Connect) for the Earnings section. */
    fun loadConnectStatus() {
        scope.launch {
            _uiState.value = _uiState.value.copy(connectStatusLoading = true, connectError = null)
            try {
                val status = apiClient.connectStatus()
                _uiState.value = _uiState.value.copy(
                    connectStatus = status,
                    connectStatusLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(connectStatusLoading = false)
            }
        }
    }

    /** Launch Stripe Connect onboarding in the browser so payouts can flow. */
    fun startConnectOnboard() {
        scope.launch {
            _uiState.value = _uiState.value.copy(connectOnboardLoading = true, connectError = null)
            try {
                val url = apiClient.connectOnboard()
                openUrl(url)
                _uiState.value = _uiState.value.copy(connectOnboardLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectOnboardLoading = false,
                    connectError = e.message ?: "Could not start payout setup",
                )
            }
        }
    }

    // ── Worker wallet: balance + cash out ──────────────────────────────

    fun loadWorkerWallet() {
        scope.launch {
            _uiState.value = _uiState.value.copy(workerWalletLoading = true)
            try {
                val wallet = apiClient.getWorkerWallet()
                _uiState.value = _uiState.value.copy(
                    workerWallet = wallet,
                    workerWalletLoading = false,
                )
            } catch (_: Exception) {
                // Older servers don't have the endpoint yet; the card just
                // hides the balance until the API is deployed.
                _uiState.value = _uiState.value.copy(workerWalletLoading = false)
            }
        }
    }

    fun openCashOutSheet() {
        _uiState.value = _uiState.value.copy(
            showCashOutSheet = true,
            cashOutAmount = "",
            cashOutMethod = if (_uiState.value.connectStatus?.payoutsReady == true) "stripe" else "check",
            cashOutError = null,
            cashOutSuccess = null,
        )
    }

    fun dismissCashOutSheet() {
        _uiState.value = _uiState.value.copy(showCashOutSheet = false)
    }

    fun updateCashOutAmount(v: String) {
        _uiState.value = _uiState.value.copy(cashOutAmount = v, cashOutError = null)
    }

    fun selectCashOutMethod(method: String) {
        _uiState.value = _uiState.value.copy(cashOutMethod = method, cashOutError = null)
    }

    /**
     * Submit the cash out. The server holds the funds atomically; the
     * idempotency key (one per call) means a network retry can't double-pay.
     */
    fun submitCashOut(amountCents: Int) {
        val method = _uiState.value.cashOutMethod
        val key = buildString(32) {
            val hex = "0123456789abcdef"
            repeat(32) { append(hex[kotlin.random.Random.nextInt(16)]) }
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(cashOutInFlight = true, cashOutError = null)
            try {
                val response = apiClient.requestWithdrawal(
                    WithdrawRequest(
                        amountCents = amountCents,
                        method = method,
                        idempotencyKey = key,
                    )
                )
                val amount = com.gcc.shared.util.GccFormatters.formatCents(amountCents)
                _uiState.value = _uiState.value.copy(
                    cashOutInFlight = false,
                    showCashOutSheet = false,
                    cashOutSuccess = when (method) {
                        "check" -> "$amount check requested — it will be mailed to your address on file."
                        else -> "$amount is on the way to your bank."
                    },
                    workerWallet = _uiState.value.workerWallet?.copy(
                        balanceCents = response.balanceCents,
                    ),
                )
                loadWorkerWallet()
                loadMyPayouts()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    cashOutInFlight = false,
                    cashOutError = e.message ?: "Cash out failed",
                )
            }
        }
    }

    fun clearCashOutSuccess() {
        _uiState.value = _uiState.value.copy(cashOutSuccess = null)
    }

    // ── Payout method (how ad-hoc payouts reach this worker) ──────────

    fun loadPayoutMethod() {
        scope.launch {
            try {
                val info = apiClient.getPayoutMethod()
                _uiState.value = _uiState.value.copy(payoutMethodInfo = info)
            } catch (_: Exception) {
                // Non-critical: the card just shows the Stripe default.
            }
        }
    }

    fun loadMyPayouts() {
        scope.launch {
            try {
                val response = apiClient.getMyPayouts()
                _uiState.value = _uiState.value.copy(myPayouts = response.payouts)
            } catch (_: Exception) {
                // History is non-critical; silently fail like transactions.
            }
        }
    }

    /** Switch back to direct deposit (Stripe Connect). */
    fun selectStripeMethod() {
        savePayoutMethod(UpdatePayoutMethodRequest(method = "stripe"))
    }

    /**
     * Switch to paper checks. When no usable address exists yet the form
     * opens instead so the worker can enter one.
     */
    fun selectCheckMethod() {
        val info = _uiState.value.payoutMethodInfo
        if (info?.effectiveMailingAddress?.isComplete == true) {
            savePayoutMethod(UpdatePayoutMethodRequest(method = "check"))
        } else {
            openCheckAddressForm()
        }
    }

    fun openCheckAddressForm() {
        val info = _uiState.value.payoutMethodInfo
        val prefill = info?.mailingAddressOverride ?: info?.effectiveMailingAddress
        _uiState.value = _uiState.value.copy(
            showCheckAddressForm = true,
            payoutMethodError = null,
            checkAddressName = prefill?.name ?: "",
            checkAddressStreet = prefill?.street ?: "",
            checkAddressCity = prefill?.city ?: "",
            checkAddressState = prefill?.state ?: "",
            checkAddressZip = prefill?.zip ?: "",
        )
    }

    fun dismissCheckAddressForm() {
        _uiState.value = _uiState.value.copy(showCheckAddressForm = false)
    }

    fun updateCheckAddressName(v: String) { _uiState.value = _uiState.value.copy(checkAddressName = v) }
    fun updateCheckAddressStreet(v: String) { _uiState.value = _uiState.value.copy(checkAddressStreet = v) }
    fun updateCheckAddressCity(v: String) { _uiState.value = _uiState.value.copy(checkAddressCity = v) }
    fun updateCheckAddressState(v: String) { _uiState.value = _uiState.value.copy(checkAddressState = v) }
    fun updateCheckAddressZip(v: String) { _uiState.value = _uiState.value.copy(checkAddressZip = v) }

    fun saveCheckAddress() {
        val state = _uiState.value
        val address = MailingAddress(
            name = state.checkAddressName.trim(),
            street = state.checkAddressStreet.trim(),
            city = state.checkAddressCity.trim(),
            state = state.checkAddressState.trim(),
            zip = state.checkAddressZip.trim(),
        )
        if (!address.isComplete) {
            _uiState.value = state.copy(
                payoutMethodError = "Street, city, state, and ZIP are all required",
            )
            return
        }
        savePayoutMethod(UpdatePayoutMethodRequest(method = "check", mailingAddress = address))
    }

    private fun savePayoutMethod(request: UpdatePayoutMethodRequest) {
        scope.launch {
            _uiState.value = _uiState.value.copy(payoutMethodSaving = true, payoutMethodError = null)
            try {
                val info = apiClient.updatePayoutMethod(request)
                _uiState.value = _uiState.value.copy(
                    payoutMethodInfo = info,
                    payoutMethodSaving = false,
                    showCheckAddressForm = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    payoutMethodSaving = false,
                    payoutMethodError = e.message ?: "Could not update payout method",
                )
            }
        }
    }

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
