package com.gcc.server

import com.gcc.server.db.DatabaseFactory
import com.gcc.server.plugins.*
import com.gcc.server.routes.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

/**
 * GCC Ktor Server entry point.
 *
 * Startup order matters:
 * 1. DI (Koin) — creates service singletons
 * 2. Database — connects to Neon PostgreSQL via HikariCP
 * 3. Plugins — serialization, CORS, auth, error handling
 * 4. Routes — HTTP endpoint registration
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 7310
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // 1. Dependency injection (must be first — routes resolve services from Koin)
    configureDI()

    // 2. Database connection pool
    DatabaseFactory.init()

    // 3. Ktor plugins
    configureSerialization()
    configureCors()
    configureAuthentication()
    configureSecurity()
    configureStatusPages()

    // 4. HTTP routes
    configureRouting()

    log.info("GCC API server started on port ${System.getenv("PORT") ?: "7310"}")
}
