package com.gcc.server.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class TwoFactorEntry(
    val code: String,
    val workerId: Int,
    val email: String,
    val expiresAt: Long,
)

class AuthService(
    private val workerService: WorkerService,
    // JWT config has env-var defaults so production wiring is unchanged.
    // Tests inject known values instead of mutating process environment.
    private val jwtSecret: String = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET environment variable must be set"),
    private val jwtIssuer: String = System.getenv("JWT_ISSUER") ?: "gcc-api",
    private val jwtAudience: String = System.getenv("JWT_AUDIENCE") ?: "gcc-app",
    adminEmailsEnv: String? = System.getenv("ADMIN_EMAILS"),
) {
    private val tokenLifetimeMs = 7L * 24 * 60 * 60 * 1000 // 7 days
    private val adminEmails = adminEmailsEnv
        ?.split(",")?.map { it.trim().lowercase() } ?: emptyList()

    private val twoFactorCodes = ConcurrentHashMap<String, TwoFactorEntry>()

    suspend fun register(request: RegisterRequest): AuthResponse {
        val existing = workerService.getByEmail(request.email)
        if (existing != null) {
            throw IllegalArgumentException("Email already registered")
        }

        // Generate salt and hash (Payload CMS style: SHA-256 of salt + password)
        val salt = generateSalt()
        val hash = hashPassword(salt, request.password)

        val now = Clock.System.now()
        val workerId = dbQuery {
            Workers.insert {
                it[name] = request.name
                it[email] = request.email
                it[phone] = request.phone
                it[Workers.salt] = salt
                it[Workers.hash] = hash
                it[emailVerified] = false
                // Address flat columns
                it[addressStreet] = ""
                it[addressCity] = request.addressCity ?: ""
                it[addressState] = request.addressState ?: ""
                it[addressZip] = ""
                // Verification flat columns (defaults)
                it[verificationStatus] = "pending"
                it[verificationGovernmentIdStatus] = "pending"
                it[verificationProofOfAddressStatus] = "pending"
                // Stripe flat columns (defaults)
                it[stripeOnboardingStatus] = "not_started"
                it[stripeInstantPayoutsEnabled] = false
                it[stripeDefaultPayoutMethod] = "standard"
                // Stats flat columns (defaults)
                it[statsJobsCompleted] = "0"
                it[statsTotalEarnings] = "0"
                // Tier and referral
                it[earlyAdopterTier] = "standard"
                it[referralCode] = generateReferralCode(request.name)
                // Notification flat columns (defaults)
                it[notificationsPushEnabled] = false
                it[notificationsSmsEnabled] = true
                it[notificationsEmailEnabled] = true
                // Timestamps
                it[createdAt] = now
                it[updatedAt] = now
            }[Workers.id]
        }

        val worker = workerService.getById(workerId)
            ?: throw IllegalStateException("Failed to retrieve worker after registration")

        val role = if (request.email.lowercase() in adminEmails) "admin" else "worker"
        val expiresAt = System.currentTimeMillis() + tokenLifetimeMs
        val token = generateToken(workerId, role, expiresAt)
        return AuthResponse(token = token, worker = worker, exp = expiresAt / 1000)
    }

    // Apple review demo account — bypasses 2FA so reviewers can log in
    private val demoEmail = System.getenv("DEMO_ACCOUNT_EMAIL")?.lowercase() ?: "applereview@gcc.com"

    suspend fun login(request: LoginRequest): AuthResponse {
        val credentials = workerService.getAuthCredentials(request.email)
            ?: throw IllegalArgumentException("Invalid email or password")

        val (salt, storedHash) = credentials
        val computedHash = hashPassword(salt, request.password)

        if (computedHash != storedHash) {
            throw IllegalArgumentException("Invalid email or password")
        }

        val worker = workerService.getByEmail(request.email)
            ?: throw IllegalStateException("Worker not found after password verification")

        // Skip 2FA for the Apple review demo account so reviewers can log in
        if (request.email.lowercase() == demoEmail) {
            val role = if (request.email.lowercase() in adminEmails) "admin" else "worker"
            val expiresAt = System.currentTimeMillis() + tokenLifetimeMs
            val token = generateToken(worker.id, role, expiresAt)
            return AuthResponse(token = token, worker = worker, exp = expiresAt / 1000)
        }

        // Generate 2FA code and return challenge
        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        val twoFactorToken = UUID.randomUUID().toString()
        val ttlMs = 5L * 60 * 1000 // 5 minutes

        twoFactorCodes[twoFactorToken] = TwoFactorEntry(
            code = code,
            workerId = worker.id,
            email = request.email,
            expiresAt = System.currentTimeMillis() + ttlMs,
        )

        // 2FA code delivery: in production, send via SMS/email. For dev, check server logs.

        return AuthResponse(
            requiresTwoFactor = true,
            twoFactorToken = twoFactorToken,
        )
    }

    suspend fun verify2FA(twoFactorToken: String, code: String): AuthResponse {
        val entry = twoFactorCodes[twoFactorToken]
            ?: throw IllegalArgumentException("Invalid or expired verification token")

        if (System.currentTimeMillis() > entry.expiresAt) {
            twoFactorCodes.remove(twoFactorToken)
            throw IllegalArgumentException("Verification code has expired")
        }

        if (entry.code != code) {
            throw IllegalArgumentException("Invalid verification code")
        }

        // Code is valid — remove it and issue JWT
        twoFactorCodes.remove(twoFactorToken)

        val worker = workerService.getById(entry.workerId)
            ?: throw IllegalStateException("Worker not found")

        val role = if (entry.email.lowercase() in adminEmails) "admin" else "worker"
        val expiresAt = System.currentTimeMillis() + tokenLifetimeMs
        val token = generateToken(worker.id, role, expiresAt)
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

    /**
     * Generate a cryptographically secure random salt (hex string).
     * Matches site's crypto.randomBytes(16).toString('hex') → 32 hex chars.
     */
    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    /**
     * Hash a password using PBKDF2 to match the production site.
     * Site uses: crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
     */
    private fun hashPassword(salt: String, password: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 100_000, 512)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val hashBytes = factory.generateSecret(spec).encoded
        return hashBytes.toHex()
    }

    /** Convert a byte array to a lowercase hex string. */
    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
