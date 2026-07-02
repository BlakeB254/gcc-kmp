package com.gcc.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.BountySpotMode
import com.gcc.app.viewmodel.WalletViewModel
import com.gcc.app.util.padTwo
import com.gcc.shared.constants.GccConstants
import com.gcc.shared.model.*
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<WalletViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadWallet()
        viewModel.loadTransactions()
        viewModel.loadConnectStatus()
    }

    // Block Cleaning bottom sheet
    if (uiState.showBlockSheet) {
        BlockCleaningSheet(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissBlockSheet() },
        )
    }

    // Bounty Posting bottom sheet
    if (uiState.showBountySheet) {
        PostBountySheet(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissBountySheet() },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("My Wallet") },
                actions = { GccLogo(size = 72.dp, modifier = Modifier.padding(end = 12.dp)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccGreen)
            }
        } else if (uiState.wallet == null && uiState.error != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Could not load wallet", color = TextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(uiState.error!!, style = MaterialTheme.typography.bodySmall, color = GccError)
                }
            }
        } else {
            val wallet = uiState.wallet
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // ── Earnings & payouts (worker) — how the money reaches you.
                // Payouts are automatic on job approval via Stripe Connect, so
                // the "cash out" story is payout readiness, front and center.
                item {
                    SectionHeader(title = "Your Earnings")
                }
                item {
                    val connectStatus = uiState.connectStatus
                    val payoutsReady = connectStatus?.payoutsReady == true
                    GccCard(
                        accentColor = if (payoutsReady) CardAccentGreen else CardAccentSky,
                        elevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (payoutsReady) "Payouts active ✓" else "Get paid for your work",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (payoutsReady) GccGreen else MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (payoutsReady) {
                                        "Route and bounty earnings are deposited to your bank automatically as soon as your work is approved."
                                    } else {
                                        "Connect your bank once — after that, every approved route or bounty pays out to you automatically. No cash-out step."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            if (uiState.connectStatusLoading || uiState.connectOnboardLoading) {
                                Spacer(modifier = Modifier.width(12.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = GccGreen,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        if (!payoutsReady && !uiState.connectStatusLoading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            GccButton(
                                text = if (connectStatus != null && connectStatus.onboardingStatus != "not_started") {
                                    "Finish payout setup"
                                } else {
                                    "Set up payouts"
                                },
                                onClick = { viewModel.startConnectOnboard() },
                                enabled = !uiState.connectOnboardLoading,
                            )
                        }
                        uiState.connectError?.let { err ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(err, color = GccError, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // ── Sponsor funds ──
                // Total balance
                if (wallet != null) {
                    item {
                        val totalDollars = wallet.totalBalance / 100
                        val totalCents = wallet.totalBalance % 100
                        GccCard(accentColor = CardAccentGreen, elevation = 4.dp) {
                            Text(
                                "Sponsor Balance",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "$$totalDollars.${totalCents.padTwo()}",
                                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                color = GccGreen,
                            )
                        }
                    }

                    // Fund breakdown
                    item {
                        SectionHeader(title = "Sponsor Funds")
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatCard(
                                value = formatCents(wallet.generalFundBalance),
                                label = "General",
                                accentColor = CardAccentGreen,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                value = formatCents(wallet.routeFundBalance),
                                label = "Route",
                                accentColor = CardAccentBlue,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                value = formatCents(wallet.bountyFundBalance),
                                label = "Bounty",
                                accentColor = CardAccentSky,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Lifetime stats
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatCard(
                                value = formatCents(wallet.totalDeposited),
                                label = "Deposited",
                                accentColor = CardAccentGreen,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                value = formatCents(wallet.totalSpent),
                                label = "Spent",
                                accentColor = CardAccentRed,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Sponsor actions
                item {
                    SectionHeader(title = "Sponsor Cleaning")
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GccCard(
                            accentColor = CardAccentGreen,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "Block Cleaning",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Pick a block, date & time",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "$BLOCK_SPONSORSHIP_RATE_LABEL flat rate",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = GccGreen,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            GccButton(
                                text = "Sponsor Block",
                                onClick = { viewModel.openBlockSheet() },
                                variant = GccButtonVariant.PRIMARY,
                            )
                        }
                        GccCard(
                            accentColor = CardAccentSky,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "Post a Bounty",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Target a spot, set reward",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Min $MIN_BOUNTY_RATE_LABEL, you choose",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = GccSky,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            GccButton(
                                text = "Post Bounty",
                                onClick = { viewModel.openBountySheet() },
                                variant = GccButtonVariant.SECONDARY,
                            )
                        }
                    }
                }

                // Deposit section
                item {
                    SectionHeader(title = "Add Funds")
                }

                item {
                    GccCard {
                        OutlinedTextField(
                            value = uiState.depositAmount,
                            onValueChange = { value ->
                                if (value.matches(DECIMAL_INPUT_REGEX)) {
                                    viewModel.updateDepositAmount(value)
                                }
                            },
                            label = { Text("Amount") },
                            placeholder = { Text("Enter amount") },
                            prefix = { Text("$ ") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GccGreen,
                                cursorColor = GccGreen,
                            ),
                        )

                        uiState.depositError?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        GccButton(
                            text = "Deposit",
                            onClick = {
                                parseDollarsToCents(uiState.depositAmount)?.let { cents ->
                                    if (cents > 0) viewModel.deposit(cents)
                                }
                            },
                            variant = GccButtonVariant.PRIMARY,
                            enabled = !uiState.isDepositing && (parseDollarsToCents(uiState.depositAmount) ?: 0) > 0,
                            isLoading = uiState.isDepositing,
                        )
                    }
                }

                // Allocate section
                if (wallet != null && wallet.generalFundBalance > 0) {
                    item {
                        SectionHeader(title = "Allocate Funds")
                    }

                    item {
                        AllocateFundsSection(
                            generalBalance = wallet.generalFundBalance,
                            isAllocating = uiState.isAllocating,
                            allocateError = uiState.allocateError,
                            onAllocate = { general, route, bounty ->
                                viewModel.allocateFunds(general, route, bounty)
                            },
                        )
                    }
                }

                // Transaction history
                if (uiState.transactions.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Recent Transactions")
                    }

                    items(uiState.transactions, key = { it.id }) { transaction ->
                        TransactionListItem(transaction)
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Block Cleaning Bottom Sheet ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockCleaningSheet(
    uiState: com.gcc.app.viewmodel.WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sponsor a Block Cleaning",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextMuted,
                    )
                }
            }
            Text(
                "Choose an available block, pick a date and preferred time. Flat rate: $BLOCK_SPONSORSHIP_RATE_LABEL.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            if (uiState.blockSuccess) {
                // Success state
                GccCard(accentColor = CardAccentGreen) {
                    Text(
                        "Checkout opened!",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = GccGreen,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Complete your payment in the browser. Once confirmed, you'll receive a notification when a worker is assigned to your block.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    GccButton(
                        text = "Done",
                        onClick = onDismiss,
                        variant = GccButtonVariant.PRIMARY,
                    )
                }
            } else {
                // Block selector
                Text(
                    "Select Block",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                if (uiState.isLoadingBlocks) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(24.dp))
                    }
                } else if (uiState.availableBlocks.isEmpty()) {
                    GccCard(accentColor = CardAccentBlue) {
                        Text(
                            "No blocks available right now. Check back soon!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.availableBlocks.forEach { block ->
                            val isSelected = uiState.selectedBlock?.id == block.id
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) GccGreen.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (isSelected) BorderStroke(2.dp, GccGreen) else null,
                                onClick = { viewModel.selectBlock(block) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (block.displayImage != null) {
                                        NetworkImage(
                                            url = block.displayImage!!,
                                            contentDescription = block.name,
                                            modifier = Modifier.size(56.dp),
                                            decodeWidth = 128,
                                            decodeHeight = 128,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(GccGreen.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                block.code.takeLast(3),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = GccGreen,
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            block.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            block.code,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = GccGreen,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Date picker
                Text(
                    "Preferred Date",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                DatePickerField(
                    value = uiState.blockDate,
                    onDateSelected = { viewModel.updateBlockDate(it) },
                )

                // Time preference
                Text(
                    "Preferred Time",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                TimePreferencePicker(
                    selectedTime = uiState.blockTime,
                    onTimeSelected = { viewModel.updateBlockTime(it) },
                )

                // Poster identity
                PosterIdentitySection(
                    isAnonymous = uiState.isAnonymous,
                    posterName = uiState.posterName,
                    posterEmail = uiState.posterEmail,
                    onAnonymousChanged = { viewModel.setAnonymous(it) },
                    onNameChanged = { viewModel.updatePosterName(it) },
                    onEmailChanged = { viewModel.updatePosterEmail(it) },
                    accentColor = GccGreen,
                )

                // Price display
                GccCard(accentColor = CardAccentGreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Block Cleaning Rate",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            formatCents(GccConstants.BLOCK_SPONSORSHIP_CENTS),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = GccGreen,
                        )
                    }
                }

                // Error
                uiState.blockError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                }

                // Confirm — opens Stripe Checkout in browser
                GccButton(
                    text = "Proceed to Payment",
                    onClick = { viewModel.confirmBlockSponsorship() },
                    variant = GccButtonVariant.PRIMARY,
                    enabled = !uiState.isSponsoringBlock,
                    isLoading = uiState.isSponsoringBlock,
                )
            }
        }
    }
}

// ── Post Bounty Bottom Sheet ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostBountySheet(
    uiState: com.gcc.app.viewmodel.WalletUiState,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // Header
                Text(
                    "Post a Bounty",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Target a dirty spot with a cash bounty for cleanup. Minimum \$10.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }

            if (uiState.bountySuccess) {
                item {
                    GccCard(accentColor = CardAccentSky) {
                        Text(
                            "Bounty posted!",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = GccSky,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Workers in the area will be notified. You'll get updates as the bounty progresses.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        GccButton(
                            text = "Done",
                            onClick = onDismiss,
                            variant = GccButtonVariant.SECONDARY,
                        )
                    }
                }
            } else {
                // Visibility toggle
                item {
                    Text(
                        "Visibility",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ToggleChip(
                            label = "Public",
                            selected = uiState.bountyVisibility == BountyVisibility.PUBLIC,
                            onClick = { viewModel.setBountyVisibility(BountyVisibility.PUBLIC) },
                            modifier = Modifier.weight(1f),
                            activeColor = GccSky,
                        )
                        ToggleChip(
                            label = "Secret",
                            selected = uiState.bountyVisibility == BountyVisibility.SECRET,
                            onClick = { viewModel.setBountyVisibility(BountyVisibility.SECRET) },
                            modifier = Modifier.weight(1f),
                            activeColor = GccSky,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (uiState.bountyVisibility == BountyVisibility.PUBLIC)
                            "Visible to all workers in the area"
                        else
                            "Only the spotter who reported it can see this bounty",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                }

                // Spot mode toggle
                item {
                    Text(
                        "Target Spot",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ToggleChip(
                            label = "Existing Spot",
                            selected = uiState.bountySpotMode == BountySpotMode.EXISTING,
                            onClick = { viewModel.setBountySpotMode(BountySpotMode.EXISTING) },
                            modifier = Modifier.weight(1f),
                            activeColor = GccSky,
                        )
                        ToggleChip(
                            label = "New Spot",
                            selected = uiState.bountySpotMode == BountySpotMode.NEW,
                            onClick = { viewModel.setBountySpotMode(BountySpotMode.NEW) },
                            modifier = Modifier.weight(1f),
                            activeColor = GccSky,
                        )
                    }
                }

                // Spot selector or new spot message
                item {
                    if (uiState.bountySpotMode == BountySpotMode.EXISTING) {
                        if (uiState.isLoadingSpots) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = GccSky, modifier = Modifier.size(24.dp))
                            }
                        } else if (uiState.nearbySpots.isEmpty()) {
                            GccCard(accentColor = CardAccentBlue) {
                                Text(
                                    "No spots reported yet. Use Spot Report from the dashboard to create one first.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.nearbySpots.take(8).forEach { spot ->
                                    val isSelected = uiState.selectedSpot?.id == spot.id
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) GccSky.copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isSelected) BorderStroke(2.dp, GccSky) else null,
                                        onClick = { viewModel.selectSpot(spot) },
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            // Title + check row
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        spot.description ?: "Spot #${spot.id}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (spot.tags.isNotEmpty()) {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            spot.tags.take(3).forEach { tag ->
                                                                Text(
                                                                    tag.name.lowercase().replaceFirstChar { it.uppercase() },
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = GccSky,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                if (isSelected) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = GccSky,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }

                                            // Map + photos row
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth().height(80.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                // Minimap showing spot location with cross streets
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                        .clip(RoundedCornerShape(6.dp)),
                                                ) {
                                                    com.gcc.app.ui.map.GccMap(
                                                        modifier = Modifier.fillMaxSize(),
                                                        centerLat = spot.coordinates.lat,
                                                        centerLng = spot.coordinates.lng,
                                                        zoom = 15.5,
                                                        currentLocation = spot.coordinates,
                                                        enableGestures = false,
                                                        showBuildings = false,
                                                        showGridLines = false,
                                                    )
                                                }

                                                // Spot report photos
                                                if (spot.mediaUrls.isNotEmpty()) {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        spot.mediaUrls.take(2).forEach { url ->
                                                            if (url.startsWith("http")) {
                                                                NetworkImage(
                                                                    url = url,
                                                                    contentDescription = "Spot photo",
                                                                    modifier = Modifier
                                                                        .size(
                                                                            width = 64.dp,
                                                                            height = if (spot.mediaUrls.size == 1) 80.dp else 38.dp,
                                                                        )
                                                                        .clip(RoundedCornerShape(6.dp)),
                                                                    decodeWidth = 128,
                                                                    decodeHeight = 128,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // Trash type tags
                                            if (spot.tags.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    spot.tags.take(4).forEach { tag ->
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = GccSky.copy(alpha = 0.1f),
                                                        ) {
                                                            Text(
                                                                tag.name.lowercase().replaceFirstChar { it.uppercase() },
                                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                                color = GccSky,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        GccCard(accentColor = CardAccentSky) {
                            Text(
                                "To post a bounty at a new location, you need to report a spot first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Use the Spot Report feature from the dashboard to photograph and pin a new dirty area, then come back to attach a bounty to it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSubtle,
                            )
                        }
                    }
                }

                // Bounty details
                item {
                    Text(
                        "Bounty Details",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = uiState.bountyTitle,
                        onValueChange = { viewModel.updateBountyTitle(it) },
                        label = { Text("Title") },
                        placeholder = { Text("e.g. Tire pile on Pulaski") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GccSky,
                            cursorColor = GccSky,
                        ),
                    )
                }

                item {
                    OutlinedTextField(
                        value = uiState.bountyDescription,
                        onValueChange = { viewModel.updateBountyDescription(it) },
                        label = { Text("Description (optional)") },
                        placeholder = { Text("Describe the situation...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GccSky,
                            cursorColor = GccSky,
                        ),
                    )
                }

                // Amount
                item {
                    Text(
                        "Reward Amount",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = uiState.bountyAmount,
                        onValueChange = { value ->
                            if (value.matches(WHOLE_INPUT_REGEX)) {
                                viewModel.updateBountyAmount(value)
                            }
                        },
                        label = { Text("Amount") },
                        prefix = { Text("$ ") },
                        placeholder = { Text("Min 10") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GccSky,
                            cursorColor = GccSky,
                        ),
                    )

                    // Quick amount buttons
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("10", "25", "50", "100").forEach { amount ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = if (uiState.bountyAmount == amount) GccSky.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (uiState.bountyAmount == amount) BorderStroke(1.dp, GccSky) else null,
                                onClick = { viewModel.updateBountyAmount(amount) },
                            ) {
                                Text(
                                    "$$amount",
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (uiState.bountyAmount == amount) GccSky else TextMuted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // Poster identity
                item {
                    PosterIdentitySection(
                        isAnonymous = uiState.isAnonymous,
                        posterName = uiState.posterName,
                        posterEmail = null,
                        onAnonymousChanged = { viewModel.setAnonymous(it) },
                        onNameChanged = { viewModel.updatePosterName(it) },
                        onEmailChanged = null,
                        accentColor = GccSky,
                        )
                }

                // Error
                uiState.bountyError?.let { error ->
                    item {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                }

                // Confirm
                item {
                    GccButton(
                        text = "Post Bounty",
                        onClick = { viewModel.confirmPostBounty() },
                        variant = GccButtonVariant.SECONDARY,
                        enabled = !uiState.isPostingBounty,
                        isLoading = uiState.isPostingBounty,
                    )
                }
            }
        }
    }
}

// ── Reusable Composables ─────────────────────────────────────────────

@Composable
private fun PosterIdentitySection(
    isAnonymous: Boolean,
    posterName: String,
    posterEmail: String?,
    onAnonymousChanged: (Boolean) -> Unit,
    onNameChanged: (String) -> Unit,
    onEmailChanged: ((String) -> Unit)?,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Your Info",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleChip(
                label = "Show My Name",
                selected = !isAnonymous,
                onClick = { onAnonymousChanged(false) },
                modifier = Modifier.weight(1f),
                activeColor = accentColor,
            )
            ToggleChip(
                label = "Anonymous",
                selected = isAnonymous,
                onClick = { onAnonymousChanged(true) },
                modifier = Modifier.weight(1f),
                activeColor = accentColor,
            )
        }

        Text(
            if (isAnonymous)
                "Your identity will be hidden on the sponsor wall and public listings"
            else
                "Your name will appear on the sponsor wall and public listings",
            style = MaterialTheme.typography.bodySmall,
            color = TextSubtle,
        )

        if (!isAnonymous) {
            OutlinedTextField(
                value = posterName,
                onValueChange = onNameChanged,
                label = { Text("Display Name") },
                placeholder = { Text("How you'd like to be listed") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    cursorColor = accentColor,
                ),
            )
        }

        if (onEmailChanged != null) {
            OutlinedTextField(
                value = posterEmail ?: "",
                onValueChange = onEmailChanged,
                label = { Text("Email") },
                placeholder = { Text("For payment receipt") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    cursorColor = accentColor,
                ),
            )
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: androidx.compose.ui.graphics.Color = GccGreen,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) activeColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(2.dp, activeColor) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        onClick = onClick,
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) activeColor else TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun DatePickerField(
    value: String,
    onDateSelected: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        onClick = { showPicker = true },
    ) {
        Text(
            text = value.ifEmpty { "Tap to select a date" },
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isEmpty()) TextMuted else MaterialTheme.colorScheme.onSurface,
        )
    }

    if (showPicker) {
        DatePickerDialog(
            onConfirm = { millis ->
                if (millis != null) {
                    val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
                    val date = instant.toLocalDateTime(TimeZone.UTC).date
                    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                    onDateSelected("${monthNames[date.monthNumber - 1]} ${date.dayOfMonth}, ${date.year}")
                }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDatePickerState()

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(datePickerState.selectedDateMillis) }) {
                Text("Confirm", color = GccGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            colors = DatePickerDefaults.colors(
                selectedDayContainerColor = GccGreen,
                todayDateBorderColor = GccGreen,
            ),
        )
    }
}

@Composable
private fun TimePreferencePicker(
    selectedTime: String,
    onTimeSelected: (String) -> Unit,
) {
    val options = listOf("Morning (7-10 AM)", "Midday (10 AM-1 PM)", "Afternoon (1-4 PM)", "Evening (4-7 PM)")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = selectedTime == option
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) GccGreen.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = if (isSelected) BorderStroke(2.dp, GccGreen)
                else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                onClick = { onTimeSelected(option) },
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) GccGreen else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = GccGreen,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Existing Composables ──────────────────────────────────────────────

@Composable
private fun AllocateFundsSection(
    generalBalance: Int,
    isAllocating: Boolean,
    allocateError: String?,
    onAllocate: (general: Int, route: Int, bounty: Int) -> Unit,
) {
    var routeAmount by remember { mutableStateOf("") }
    var bountyAmount by remember { mutableStateOf("") }

    GccCard {
        Text(
            "Move funds from General to Route or Bounty",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Text(
            "Available: ${formatCents(generalBalance)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = GccGreen,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = routeAmount,
                onValueChange = { if (it.matches(DECIMAL_INPUT_REGEX)) routeAmount = it },
                label = { Text("Route") },
                prefix = { Text("$ ") },
                placeholder = { Text("0.00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GccBlue,
                    cursorColor = GccBlue,
                ),
            )
            OutlinedTextField(
                value = bountyAmount,
                onValueChange = { if (it.matches(DECIMAL_INPUT_REGEX)) bountyAmount = it },
                label = { Text("Bounty") },
                prefix = { Text("$ ") },
                placeholder = { Text("0.00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GccSky,
                    cursorColor = GccSky,
                ),
            )
        }

        allocateError?.let { error ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
        }

        Spacer(modifier = Modifier.height(12.dp))

        val routeVal = parseDollarsToCents(routeAmount) ?: 0
        val bountyVal = parseDollarsToCents(bountyAmount) ?: 0
        val totalAllocate = routeVal + bountyVal
        val remaining = generalBalance - totalAllocate

        GccButton(
            text = "Allocate",
            onClick = { onAllocate(remaining.coerceAtLeast(0), routeVal, bountyVal) },
            variant = GccButtonVariant.SECONDARY,
            enabled = !isAllocating && totalAllocate > 0 && totalAllocate <= generalBalance,
            isLoading = isAllocating,
        )
    }
}

@Composable
private fun TransactionListItem(
    transaction: WalletTransaction,
    modifier: Modifier = Modifier,
) {
    val isCredit = transaction.type == WalletTransactionType.DEPOSIT ||
        transaction.type == WalletTransactionType.REFUND
    val accentColor = if (isCredit) CardAccentGreen else CardAccentRed
    val sign = if (isCredit) "+" else "-"
    val dollars = transaction.amount / 100
    val cents = transaction.amount % 100

    GccCard(
        modifier = modifier,
        accentColor = accentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transactionTypeLabel(transaction.type),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    fundTypeLabel(transaction.fundType),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                transaction.description?.let { desc ->
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                        maxLines = 1,
                    )
                }
            }

            Text(
                "$sign$$dollars.${cents.padTwo()}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isCredit) GccGreen else GccError,
            )
        }
    }
}

private val DECIMAL_INPUT_REGEX = Regex("^\\d*\\.?\\d{0,2}$")
private val WHOLE_INPUT_REGEX = Regex("^\\d{0,5}$")

// Derived display labels — sourced from GccConstants so a price change is one edit
private val BLOCK_SPONSORSHIP_RATE_LABEL = "\$${GccConstants.BLOCK_SPONSORSHIP_CENTS / 100}"
private val MIN_BOUNTY_RATE_LABEL = "\$${GccConstants.MIN_BOUNTY_CENTS / 100}"

private fun formatCents(cents: Int): String = com.gcc.shared.util.GccFormatters.formatCents(cents)

private fun parseDollarsToCents(input: String): Int? {
    if (input.isBlank()) return null
    val parts = input.split(".")
    val dollars = parts[0].toIntOrNull() ?: return null
    val cents = if (parts.size > 1) {
        val c = parts[1].padEnd(2, '0').take(2)
        c.toIntOrNull() ?: 0
    } else 0
    return dollars * 100 + cents
}

private fun transactionTypeLabel(type: WalletTransactionType): String = when (type) {
    WalletTransactionType.DEPOSIT -> "Deposit"
    WalletTransactionType.ALLOCATION -> "Allocation"
    WalletTransactionType.JOB_DEDUCTION -> "Job Payment"
    WalletTransactionType.BOUNTY_DEDUCTION -> "Bounty Payment"
    WalletTransactionType.REFUND -> "Refund"
}

private fun fundTypeLabel(type: FundType): String = when (type) {
    FundType.GENERAL -> "General Fund"
    FundType.ROUTE -> "Route Fund"
    FundType.BOUNTY -> "Bounty Fund"
}
