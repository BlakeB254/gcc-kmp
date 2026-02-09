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
            ?: listOf("http://localhost:3000", "http://localhost:3001")
        allowedOrigins.forEach { allowHost(it.removePrefix("http://").removePrefix("https://"), schemes = listOf("http", "https")) }

        allowCredentials = true
    }
}
