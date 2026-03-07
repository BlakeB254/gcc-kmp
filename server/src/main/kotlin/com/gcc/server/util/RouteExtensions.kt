package com.gcc.server.util

import io.ktor.server.routing.*

/**
 * Extracts an integer path parameter by name, throwing if missing or not a valid integer.
 */
fun RoutingCall.intPathParam(name: String): Int =
    parameters[name]?.toIntOrNull()
        ?: throw IllegalArgumentException("$name must be a valid integer")

/**
 * Extracts an optional integer query parameter.
 */
fun RoutingCall.intQueryParam(name: String, default: Int? = null): Int? =
    parameters[name]?.toIntOrNull() ?: default

/**
 * Extracts an optional long query parameter.
 */
fun RoutingCall.longQueryParam(name: String, default: Long? = null): Long? =
    parameters[name]?.toLongOrNull() ?: default
