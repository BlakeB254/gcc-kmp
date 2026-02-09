package com.gcc.server.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        healthRoutes()
        leaderboardRoutes()
        workerRoutes()
        routeRoutes()
    }
}
