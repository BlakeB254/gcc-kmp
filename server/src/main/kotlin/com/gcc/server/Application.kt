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
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        // Limit request body to 10MB (photo uploads are the largest payloads)
        connector { }
        module()
    }.start(wait = true)
}

private fun Application.configureRequestLimits() {
    // Netty's maxContentLength is configured at the engine level.
    // For Ktor 3.x, we use the receive pipeline to reject oversized bodies.
    receivePipeline.intercept(io.ktor.server.request.ApplicationReceivePipeline.Before) {
        val contentLength = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        val maxSize = 10L * 1024 * 1024 // 10 MB
        if (contentLength != null && contentLength > maxSize) {
            call.respond(io.ktor.http.HttpStatusCode.PayloadTooLarge,
                com.gcc.server.plugins.ErrorResponse("Request body too large (max 10MB)", 413))
            finish()
        }
    }
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
    configureRateLimiting()
    configureRequestLimits()
    configureStatusPages()

    // 4. HTTP routes
    configureRouting()

    log.info("GCC API server started on port ${System.getenv("PORT") ?: "7310"}")
}
