package com.gcc.server.routes

import com.gcc.server.plugins.ErrorResponse
import com.gcc.server.services.AuthService
import com.gcc.shared.model.*
import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.gcc.server.plugins.koinGet

fun Routing.authRoutes() {
    val authService = koinGet<AuthService>()

    rateLimit(RateLimitName("auth")) {
    route("/api/auth") {
        post("/register") {
            try {
                val request = call.receive<RegisterRequest>()
                val response = authService.register(request)
                call.respond(HttpStatusCode.Created, ApiResponse(data = response))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Registration failed", 409))
            }
        }

        post("/login") {
            try {
                val request = call.receive<LoginRequest>()
                val response = authService.login(request)
                call.respond(HttpStatusCode.OK, ApiResponse(data = response))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message ?: "Authentication failed", 401))
            }
        }

        post("/verify-2fa") {
            try {
                val request = call.receive<Verify2FARequest>()
                val response = authService.verify2FA(request.twoFactorToken, request.code)
                call.respond(HttpStatusCode.OK, ApiResponse(data = response))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message ?: "Verification failed", 401))
            }
        }
    }
    }
}
