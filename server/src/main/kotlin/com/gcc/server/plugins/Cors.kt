package com.gcc.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCors() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        val allowedOrigins = System.getenv("ALLOWED_ORIGINS")?.split(",")
            ?: listOf(
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:7350",
                "https://www.globalcommunitycleaners.org",
                "https://globalcommunitycleaners.org"
            )
        allowedOrigins.forEach { origin ->
            val host = origin.removePrefix("http://").removePrefix("https://")
            val schemes = if (origin.startsWith("https://")) listOf("https") else listOf("http", "https")
            allowHost(host, schemes = schemes)
        }

        // Mobile native apps (Ktor HttpClient) don't send Origin headers.
        // Requests without an Origin header bypass CORS entirely (browser-only mechanism).
        // No anyHost() — only explicitly allowed origins above.
        allowCredentials = false
    }
}
