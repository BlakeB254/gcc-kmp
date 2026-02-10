package com.gcc.server.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import java.util.*

class AuthService(
    private val workerService: WorkerService
) {
    private val jwtSecret = System.getenv("JWT_SECRET") ?: "dev-secret-change-in-production"
    private val jwtIssuer = System.getenv("JWT_ISSUER") ?: "gcc-api"
    private val jwtAudience = System.getenv("JWT_AUDIENCE") ?: "gcc-app"
    private val tokenLifetimeMs = 7L * 24 * 60 * 60 * 1000 // 7 days

    suspend fun register(request: RegisterRequest): AuthResponse {
        val existing = workerService.getByEmail(request.email)
        if (existing != null) {
            throw IllegalArgumentException("Email already registered")
        }

        val hash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray())

        val now = Clock.System.now()
        val workerId = dbQuery {
            Workers.insert {
                it[name] = request.name
                it[email] = request.email
                it[phone] = request.phone
                it[passwordHash] = hash
                it[emailVerified] = false
                it[address] = WorkerAddress(street = "", city = "Chicago", state = "IL", zip = "")
                it[verification] = WorkerVerification()
                it[stripe] = WorkerStripe()
                it[stats] = WorkerStats()
                it[earlyAdopterTier] = "standard"
                it[referralCode] = generateReferralCode(request.name)
                it[notifications] = WorkerNotifications()
                it[createdAt] = now
                it[updatedAt] = now
            }[Workers.id]
        }

        val worker = workerService.getById(workerId)
            ?: throw IllegalStateException("Failed to retrieve worker after registration")

        val expiresAt = System.currentTimeMillis() + tokenLifetimeMs
        val token = generateToken(workerId, worker.role, expiresAt)
        return AuthResponse(token = token, worker = worker, exp = expiresAt / 1000)
    }

    suspend fun login(request: LoginRequest): AuthResponse {
        val hash = workerService.getPasswordHash(request.email)
            ?: throw IllegalArgumentException("Invalid email or password")

        val result = BCrypt.verifyer().verify(request.password.toCharArray(), hash)
        if (!result.verified) {
            throw IllegalArgumentException("Invalid email or password")
        }

        val worker = workerService.getByEmail(request.email)
            ?: throw IllegalStateException("Worker not found after password verification")

        val expiresAt = System.currentTimeMillis() + tokenLifetimeMs
        val token = generateToken(worker.id, worker.role, expiresAt)
        return AuthResponse(token = token, worker = worker, exp = expiresAt / 1000)
    }

    private fun generateToken(workerId: Int, role: String, expiresAtMs: Long): String {
        return JWT.create()
            .withIssuer(jwtIssuer)
            .withAudience(jwtAudience)
            .withClaim("worker_id", workerId)
            .withClaim("role", role)
            .withExpiresAt(Date(expiresAtMs))
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun generateReferralCode(name: String): String {
        val initials = name.split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
        val random = (1000..9999).random()
        return "GCC-$initials$random"
    }
}
