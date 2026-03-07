package com.gcc.server.util

import com.gcc.server.plugins.requireAdmin
import io.ktor.server.application.*
import io.ktor.server.routing.*

/**
 * Creates a route group where all handlers require admin authentication.
 * Calls requireAdmin() once via an intercept, so individual handlers don't need to.
 */
fun Route.adminRoute(path: String, build: Route.() -> Unit) {
    route(path) {
        intercept(ApplicationCallPipeline.Plugins) {
            (call as RoutingCall).requireAdmin()
        }
        build()
    }
}
