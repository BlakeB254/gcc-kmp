package com.gcc.server.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("X-XSS-Protection", "1; mode=block")
        call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
}
