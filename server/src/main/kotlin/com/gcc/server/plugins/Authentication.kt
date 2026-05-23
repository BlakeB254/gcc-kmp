package com.gcc.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureAuthentication() {
    val jwtSecret = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET environment variable must be set")
    val jwtIssuer = System.getenv("JWT_ISSUER") ?: "gcc-api"
    val jwtAudience = System.getenv("JWT_AUDIENCE") ?: "gcc-app"

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(jwtIssuer)
                    .withAudience(jwtAudience)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }
}
