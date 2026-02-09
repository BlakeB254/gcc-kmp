package com.gcc.shared.api

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class GccApiClient(
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String? = { null }
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(this@GccApiClient.json)
        }
        install(Auth) {
            bearer {
                loadTokens {
                    tokenProvider()?.let { BearerTokens(it, "") }
                }
            }
        }
    }
}
