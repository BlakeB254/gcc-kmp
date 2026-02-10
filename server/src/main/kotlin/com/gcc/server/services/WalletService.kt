package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.SponsorWallets
import com.gcc.server.db.tables.WalletTransactions
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*

class WalletService {

    /**
     * Get or create a wallet. Exactly one of sponsorId/workerId should be set.
     * A single wallet per user is enforced by checking existing records first.
     */
    suspend fun getOrCreateWallet(sponsorId: Int? = null, workerId: Int? = null): SponsorWallet = dbQuery {
        val existing = findWallet(sponsorId, workerId)
        existing ?: createWallet(sponsorId, workerId)
    }

    suspend fun getBalance(walletId: Int): SponsorWallet? = dbQuery {
        SponsorWallets.selectAll().where { SponsorWallets.id eq walletId }
            .firstOrNull()?.toWallet()
    }

    suspend fun getWalletBySponsorId(sponsorId: Int): SponsorWallet? = dbQuery {
        SponsorWallets.selectAll().where { SponsorWallets.sponsorId eq sponsorId }
            .firstOrNull()?.toWallet()
    }

    suspend fun getWalletByWorkerId(workerId: Int): SponsorWallet? = dbQuery {
        SponsorWallets.selectAll().where { SponsorWallets.workerId eq workerId }
            .firstOrNull()?.toWallet()
    }

    /**
     * Deposit funds into the wallet. Adds to total_balance (unallocated).
     * All balance mutations are atomic within a single dbQuery transaction.
     */
    suspend fun deposit(
        walletId: Int,
        amount: Int,
        stripePaymentIntentId: String? = null,
    ): WalletTransaction = dbQuery {
        require(amount > 0) { "Deposit amount must be positive" }

        val wallet = SponsorWallets.selectAll().where { SponsorWallets.id eq walletId }
            .firstOrNull()?.toWallet()
            ?: throw IllegalArgumentException("Wallet not found")

        val newBalance = wallet.totalBalance + amount
        val now = Clock.System.now()

        SponsorWallets.update({ SponsorWallets.id eq walletId }) {
            it[totalBalance] = newBalance
            it[totalDeposited] = wallet.totalDeposited + amount
            it[updatedAt] = now
        }

        val txnId = WalletTransactions.insert {
            it[WalletTransactions.walletId] = walletId
            it[type] = WalletTransactionType.DEPOSIT.name.lowercase()
            it[fundType] = FundType.GENERAL.name.lowercase()
            it[WalletTransactions.amount] = amount
            it[balanceAfter] = newBalance
            it[referenceType] = WalletReferenceType.STRIPE_DEPOSIT.name.lowercase()
            it[WalletTransactions.stripePaymentIntentId] = stripePaymentIntentId
            it[description] = "Deposit of ${amount / 100}.${(amount % 100).toString().padStart(2, '0')} USD"
            it[createdAt] = now
        }[WalletTransactions.id]

        WalletTransactions.selectAll().where { WalletTransactions.id eq txnId }
            .first().toWalletTransaction()
    }

    /**
     * Allocate unallocated balance into the 3 fund buckets.
     * The total of all allocations must not exceed the current totalBalance.
     */
    suspend fun allocateFunds(
        walletId: Int,
        generalAmount: Int,
        routeAmount: Int,
        bountyAmount: Int,
    ): List<WalletTransaction> = dbQuery {
        val wallet = SponsorWallets.selectAll().where { SponsorWallets.id eq walletId }
            .firstOrNull()?.toWallet()
            ?: throw IllegalArgumentException("Wallet not found")

        val totalAllocation = generalAmount + routeAmount + bountyAmount
        require(totalAllocation <= wallet.totalBalance) {
            "Allocation ($totalAllocation) exceeds available balance (${wallet.totalBalance})"
        }

        val now = Clock.System.now()
        val newGeneral = wallet.generalFundBalance + generalAmount
        val newRoute = wallet.routeFundBalance + routeAmount
        val newBounty = wallet.bountyFundBalance + bountyAmount
        val newTotal = wallet.totalBalance - totalAllocation

        SponsorWallets.update({ SponsorWallets.id eq walletId }) {
            it[totalBalance] = newTotal
            it[generalFundBalance] = newGeneral
            it[routeFundBalance] = newRoute
            it[bountyFundBalance] = newBounty
            it[updatedAt] = now
        }

        val transactions = mutableListOf<WalletTransaction>()

        if (generalAmount > 0) {
            val id = WalletTransactions.insert {
                it[WalletTransactions.walletId] = walletId
                it[type] = WalletTransactionType.ALLOCATION.name.lowercase()
                it[fundType] = FundType.GENERAL.name.lowercase()
                it[amount] = generalAmount
                it[balanceAfter] = newGeneral
                it[description] = "Allocated to General fund"
                it[createdAt] = now
            }[WalletTransactions.id]
            transactions.add(
                WalletTransactions.selectAll().where { WalletTransactions.id eq id }
                    .first().toWalletTransaction()
            )
        }
        if (routeAmount > 0) {
            val id = WalletTransactions.insert {
                it[WalletTransactions.walletId] = walletId
                it[type] = WalletTransactionType.ALLOCATION.name.lowercase()
                it[fundType] = FundType.ROUTE.name.lowercase()
                it[amount] = routeAmount
                it[balanceAfter] = newRoute
                it[description] = "Allocated to Route fund"
                it[createdAt] = now
            }[WalletTransactions.id]
            transactions.add(
                WalletTransactions.selectAll().where { WalletTransactions.id eq id }
                    .first().toWalletTransaction()
            )
        }
        if (bountyAmount > 0) {
            val id = WalletTransactions.insert {
                it[WalletTransactions.walletId] = walletId
                it[type] = WalletTransactionType.ALLOCATION.name.lowercase()
                it[fundType] = FundType.BOUNTY.name.lowercase()
                it[amount] = bountyAmount
                it[balanceAfter] = newBounty
                it[description] = "Allocated to Bounty fund"
                it[createdAt] = now
            }[WalletTransactions.id]
            transactions.add(
                WalletTransactions.selectAll().where { WalletTransactions.id eq id }
                    .first().toWalletTransaction()
            )
        }

        transactions
    }

    suspend fun deductForJob(walletId: Int, fundType: FundType, amount: Int, jobId: Int): WalletTransaction =
        deduct(walletId, fundType, amount, WalletTransactionType.JOB_DEDUCTION, WalletReferenceType.JOB, jobId)

    suspend fun deductForBounty(walletId: Int, amount: Int, bountyId: Int): WalletTransaction =
        deduct(walletId, FundType.BOUNTY, amount, WalletTransactionType.BOUNTY_DEDUCTION, WalletReferenceType.BOUNTY, bountyId)

    suspend fun refund(walletId: Int, fundType: FundType, amount: Int, referenceId: Int): WalletTransaction = dbQuery {
        require(amount > 0) { "Refund amount must be positive" }
        val wallet = SponsorWallets.selectAll().where { SponsorWallets.id eq walletId }
            .firstOrNull()?.toWallet() ?: throw IllegalArgumentException("Wallet not found")

        val now = Clock.System.now()
        val newFundBalance = when (fundType) {
            FundType.GENERAL -> wallet.generalFundBalance + amount
            FundType.ROUTE -> wallet.routeFundBalance + amount
            FundType.BOUNTY -> wallet.bountyFundBalance + amount
        }

        SponsorWallets.update({ SponsorWallets.id eq walletId }) {
            when (fundType) {
                FundType.GENERAL -> it[generalFundBalance] = newFundBalance
                FundType.ROUTE -> it[routeFundBalance] = newFundBalance
                FundType.BOUNTY -> it[bountyFundBalance] = newFundBalance
            }
            it[totalSpent] = wallet.totalSpent - amount
            it[updatedAt] = now
        }

        val txnId = WalletTransactions.insert {
            it[WalletTransactions.walletId] = walletId
            it[type] = WalletTransactionType.REFUND.name.lowercase()
            it[WalletTransactions.fundType] = fundType.name.lowercase()
            it[WalletTransactions.amount] = amount
            it[balanceAfter] = newFundBalance
            it[WalletTransactions.referenceId] = referenceId
            it[description] = "Refund"
            it[createdAt] = now
        }[WalletTransactions.id]

        WalletTransactions.selectAll().where { WalletTransactions.id eq txnId }
            .first().toWalletTransaction()
    }

    suspend fun getTransactionHistory(
        walletId: Int,
        limit: Int = 50,
        offset: Long = 0,
    ): List<WalletTransaction> = dbQuery {
        WalletTransactions.selectAll()
            .where { WalletTransactions.walletId eq walletId }
            .orderBy(WalletTransactions.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toWalletTransaction() }
    }

    // ---- private helpers ----

    private fun findWallet(sponsorId: Int?, workerId: Int?): SponsorWallet? {
        return when {
            sponsorId != null -> SponsorWallets.selectAll()
                .where { SponsorWallets.sponsorId eq sponsorId }
                .firstOrNull()?.toWallet()
            workerId != null -> SponsorWallets.selectAll()
                .where { SponsorWallets.workerId eq workerId }
                .firstOrNull()?.toWallet()
            else -> throw IllegalArgumentException("Either sponsorId or workerId must be set")
        }
    }

    private fun createWallet(sponsorId: Int?, workerId: Int?): SponsorWallet {
        val now = Clock.System.now()
        val id = SponsorWallets.insert {
            it[SponsorWallets.sponsorId] = sponsorId
            it[SponsorWallets.workerId] = workerId
            it[createdAt] = now
            it[updatedAt] = now
        }[SponsorWallets.id]

        return SponsorWallets.selectAll().where { SponsorWallets.id eq id }
            .first().toWallet()
    }

    private suspend fun deduct(
        walletId: Int,
        fundType: FundType,
        amount: Int,
        txnType: WalletTransactionType,
        refType: WalletReferenceType,
        referenceId: Int,
    ): WalletTransaction = dbQuery {
        require(amount > 0) { "Deduction amount must be positive" }
        val wallet = SponsorWallets.selectAll().where { SponsorWallets.id eq walletId }
            .firstOrNull()?.toWallet() ?: throw IllegalArgumentException("Wallet not found")

        val currentFundBalance = when (fundType) {
            FundType.GENERAL -> wallet.generalFundBalance
            FundType.ROUTE -> wallet.routeFundBalance
            FundType.BOUNTY -> wallet.bountyFundBalance
        }
        require(currentFundBalance >= amount) {
            "Insufficient ${fundType.name} fund balance ($currentFundBalance < $amount)"
        }

        val newFundBalance = currentFundBalance - amount
        val now = Clock.System.now()

        SponsorWallets.update({ SponsorWallets.id eq walletId }) {
            when (fundType) {
                FundType.GENERAL -> it[generalFundBalance] = newFundBalance
                FundType.ROUTE -> it[routeFundBalance] = newFundBalance
                FundType.BOUNTY -> it[bountyFundBalance] = newFundBalance
            }
            it[totalSpent] = wallet.totalSpent + amount
            it[updatedAt] = now
        }

        val txnId = WalletTransactions.insert {
            it[WalletTransactions.walletId] = walletId
            it[type] = txnType.name.lowercase()
            it[WalletTransactions.fundType] = fundType.name.lowercase()
            it[WalletTransactions.amount] = -amount
            it[balanceAfter] = newFundBalance
            it[WalletTransactions.referenceId] = referenceId
            it[WalletTransactions.referenceType] = refType.name.lowercase()
            it[description] = "${txnType.name} of ${amount / 100}.${(amount % 100).toString().padStart(2, '0')} USD"
            it[createdAt] = now
        }[WalletTransactions.id]

        WalletTransactions.selectAll().where { WalletTransactions.id eq txnId }
            .first().toWalletTransaction()
    }
}

fun ResultRow.toWallet(): SponsorWallet = SponsorWallet(
    id = this[SponsorWallets.id],
    sponsorId = this[SponsorWallets.sponsorId],
    workerId = this[SponsorWallets.workerId],
    totalBalance = this[SponsorWallets.totalBalance],
    generalFundBalance = this[SponsorWallets.generalFundBalance],
    routeFundBalance = this[SponsorWallets.routeFundBalance],
    bountyFundBalance = this[SponsorWallets.bountyFundBalance],
    totalDeposited = this[SponsorWallets.totalDeposited],
    totalSpent = this[SponsorWallets.totalSpent],
    createdAt = this[SponsorWallets.createdAt],
    updatedAt = this[SponsorWallets.updatedAt],
)

fun ResultRow.toWalletTransaction(): WalletTransaction = WalletTransaction(
    id = this[WalletTransactions.id],
    walletId = this[WalletTransactions.walletId],
    type = enumValueOfOrDefault(this[WalletTransactions.type]),
    fundType = enumValueOfOrDefault(this[WalletTransactions.fundType]),
    amount = this[WalletTransactions.amount],
    balanceAfter = this[WalletTransactions.balanceAfter],
    referenceId = this[WalletTransactions.referenceId],
    referenceType = this[WalletTransactions.referenceType]?.let { enumValueOfOrDefault<WalletReferenceType>(it) },
    description = this[WalletTransactions.description],
    stripePaymentIntentId = this[WalletTransactions.stripePaymentIntentId],
    createdAt = this[WalletTransactions.createdAt],
)
