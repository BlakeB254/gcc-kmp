package com.gcc.server.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.core.context.GlobalContext

/**
 * Retrieve a Koin dependency from the global context.
 *
 * This replaces `org.koin.ktor.ext.get` which fails at runtime because
 * koin-ktor 4.0.x was compiled when Ktor's Routing was a class,
 * but Ktor 3.1.x made it an interface (IncompatibleClassChangeError).
 */
inline fun <reified T : Any> Route.koinGet(): T =
    GlobalContext.get().get()

inline fun <reified T : Any> Routing.koinGet(): T =
    GlobalContext.get().get()

inline fun <reified T : Any> Application.koinGet(): T =
    GlobalContext.get().get()
