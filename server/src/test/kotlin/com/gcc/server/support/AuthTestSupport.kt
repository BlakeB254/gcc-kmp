package com.gcc.server.support

import com.gcc.server.services.AuthService
import com.gcc.server.services.WorkerService
import com.gcc.shared.model.Worker
import com.gcc.shared.model.WorkerAddress
import kotlinx.datetime.Clock

/**
 * Shared in-memory fakes + builders for AuthService / auth-route tests.
 * Lives in a separate package so both routes/ and services/ test packages
 * can use it without re-declaring the same helpers.
 */

internal class FakeWorkerService(
    private val workersByEmail: Map<String, Worker>,
    private val credsByEmail: Map<String, Pair<String, String>>,
) : WorkerService() {
    override suspend fun getByEmail(email: String): Worker? = workersByEmail[email]
    override suspend fun getById(id: Int): Worker? =
        workersByEmail.values.firstOrNull { it.id == id }
    override suspend fun getAuthCredentials(email: String): Pair<String, String>? =
        credsByEmail[email]
}

internal fun stubWorker(id: Int, email: String): Worker {
    val now = Clock.System.now()
    return Worker(
        id = id,
        name = "Test Worker",
        email = email,
        phone = "+15555550100",
        address = WorkerAddress(street = "", zip = ""),
        createdAt = now,
        updatedAt = now,
    )
}

internal fun makeAuthService(
    workers: Map<String, Worker> = emptyMap(),
    creds: Map<String, Pair<String, String>> = emptyMap(),
): AuthService =
    AuthService(
        workerService = FakeWorkerService(workers, creds),
        jwtSecret = "test-secret-only-used-in-tests",
        jwtIssuer = "gcc-test",
        jwtAudience = "gcc-test",
        adminEmailsEnv = null,
    )
