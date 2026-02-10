package com.gcc.server.plugins

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*

/**
 * Extract the authenticated worker ID from the JWT "worker_id" claim.
 *
 * Must be called inside an `authenticate("auth-jwt") { }` block.
 * Throws [AuthorizationException] if the principal or claim is missing.
 */
fun RoutingCall.workerId(): Int {
    val principal = principal<JWTPrincipal>()
        ?: throw AuthorizationException("Not authenticated")
    return principal.payload.getClaim("worker_id").asInt()
        ?: throw AuthorizationException("Invalid token: missing worker_id claim")
}

/** Extract the user role from JWT "role" claim, defaulting to "worker". */
fun RoutingCall.userRole(): String {
    val principal = principal<JWTPrincipal>()
        ?: throw AuthorizationException("Not authenticated")
    return principal.payload.getClaim("role")?.asString() ?: "worker"
}

/** Assert the caller is an admin. Throws AuthorizationException if not. */
fun RoutingCall.requireAdmin() {
    val role = userRole()
    if (role != "admin" && role != "super_admin") {
        throw AuthorizationException("Admin access required")
    }
}

/** Check if the caller is an admin (without throwing). */
fun RoutingCall.isAdmin(): Boolean {
    return try {
        val role = userRole()
        role == "admin" || role == "super_admin"
    } catch (_: Exception) {
        false
    }
}

/** Extract sponsor_id from JWT if present (for sponsor auth). */
fun RoutingCall.sponsorId(): Int {
    val principal = principal<JWTPrincipal>()
        ?: throw AuthorizationException("Not authenticated")
    return principal.payload.getClaim("sponsor_id").asInt()
        ?: throw AuthorizationException("Invalid token: missing sponsor_id claim")
}

class AuthorizationException(message: String) : RuntimeException(message)
