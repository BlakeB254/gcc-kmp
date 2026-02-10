package com.gcc.server.routes

import com.gcc.server.plugins.workerId
import com.gcc.server.services.WalletService
import com.gcc.shared.model.AllocateRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.DepositRequest
import com.gcc.shared.model.SponsorWallet
import com.gcc.shared.model.WalletTransaction
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get as koinGet

/**
 * Authenticated wallet endpoints.
 *
 * GET    /api/wallet              — Get current user's wallet
 * POST   /api/wallet/deposit      — Deposit funds
 * POST   /api/wallet/allocate     — Allocate between general/route/bounty
 * GET    /api/wallet/transactions  — Transaction history (paginated)
 * GET    /api/wallet/balance       — Quick balance check
 */
fun Route.authenticatedWalletRoutes() {
    val walletService = application.koinGet<WalletService>()

    route("/api/wallet") {
        get {
            val wallet = walletService.getOrCreateWallet(workerId = call.workerId())
            call.respond(HttpStatusCode.OK, ApiResponse(data = wallet))
        }

        get("/balance") {
            val wallet = walletService.getWalletByWorkerId(call.workerId())
            if (wallet != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = wallet))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse(
                    data = null as SponsorWallet?, success = false, message = "No wallet found"
                ))
            }
        }

        post("/deposit") {
            val request = call.receive<DepositRequest>()
            val wallet = walletService.getOrCreateWallet(workerId = call.workerId())
            val txn = walletService.deposit(
                walletId = wallet.id,
                amount = request.amount,
                stripePaymentIntentId = request.stripePaymentIntentId,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = txn))
        }

        post("/allocate") {
            val request = call.receive<AllocateRequest>()
            val wallet = walletService.getOrCreateWallet(workerId = call.workerId())
            val transactions = walletService.allocateFunds(
                walletId = wallet.id,
                generalAmount = request.generalAmount,
                routeAmount = request.routeAmount,
                bountyAmount = request.bountyAmount,
            )
            call.respond(HttpStatusCode.OK, ApiResponse(data = transactions))
        }

        get("/transactions") {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val wallet = walletService.getWalletByWorkerId(call.workerId())
            if (wallet != null) {
                val transactions = walletService.getTransactionHistory(wallet.id, limit, offset)
                call.respond(HttpStatusCode.OK, ApiResponse(data = transactions))
            } else {
                call.respond(HttpStatusCode.OK, ApiResponse(data = emptyList<WalletTransaction>()))
            }
        }
    }
}
