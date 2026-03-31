package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.WalletViewModel
import com.gcc.app.util.padTwo
import com.gcc.shared.model.FundType
import com.gcc.shared.model.WalletTransaction
import com.gcc.shared.model.WalletTransactionType
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
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("My Wallet") },
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
                // Total balance
                if (wallet != null) {
                    item {
                        val totalDollars = wallet.totalBalance / 100
                        val totalCents = wallet.totalBalance % 100
                        GccCard(accentColor = CardAccentGreen, elevation = 4.dp) {
                            Text(
                                "Total Balance",
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
                        SectionHeader(title = "Fund Breakdown")
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
                                accentColor = CardAccentGold,
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

                // Deposit section
                item {
                    SectionHeader(title = "Deposit Funds")
                }

                item {
                    GccCard {
                        OutlinedTextField(
                            value = uiState.depositAmount,
                            onValueChange = { value ->
                                // Only allow digits
                                if (value.all { it.isDigit() }) {
                                    viewModel.updateDepositAmount(value)
                                }
                            },
                            label = { Text("Amount") },
                            placeholder = { Text("Enter amount in cents") },
                            prefix = { Text("¢ ") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GccGreen,
                                cursorColor = GccGreen,
                            ),
                        )

                        val previewAmount = uiState.depositAmount.toIntOrNull()
                        if (previewAmount != null && previewAmount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "= ${formatCents(previewAmount)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = GccGreen,
                            )
                        }

                        uiState.depositError?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        GccButton(
                            text = "Deposit",
                            onClick = {
                                uiState.depositAmount.toIntOrNull()?.let { amount ->
                                    if (amount > 0) viewModel.deposit(amount)
                                }
                            },
                            variant = GccButtonVariant.PRIMARY,
                            enabled = !uiState.isDepositing && (uiState.depositAmount.toIntOrNull() ?: 0) > 0,
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
                onValueChange = { if (it.all { c -> c.isDigit() }) routeAmount = it },
                label = { Text("Route") },
                placeholder = { Text("cents") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GccBlue,
                    cursorColor = GccBlue,
                ),
            )
            OutlinedTextField(
                value = bountyAmount,
                onValueChange = { if (it.all { c -> c.isDigit() }) bountyAmount = it },
                label = { Text("Bounty") },
                placeholder = { Text("cents") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GccGold,
                    cursorColor = GccGold,
                ),
            )
        }

        allocateError?.let { error ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
        }

        Spacer(modifier = Modifier.height(12.dp))

        val routeVal = routeAmount.toIntOrNull() ?: 0
        val bountyVal = bountyAmount.toIntOrNull() ?: 0
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

private fun formatCents(cents: Int): String {
    val dollars = cents / 100
    val remainder = cents % 100
    return "$$dollars.${remainder.toString().padStart(2, '0')}"
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
