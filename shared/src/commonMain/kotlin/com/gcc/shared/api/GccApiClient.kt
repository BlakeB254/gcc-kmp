package com.gcc.shared.api

import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.LeaderboardEntry
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class GccApiClient(
    val baseUrl: String,
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

    suspend fun getLeaderboard(period: String = "all_time", limit: Int = 50): List<LeaderboardEntry> {
        val response = httpClient.get("$baseUrl/api/leaderboard") {
            parameter("period", period)
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<LeaderboardEntry>>>().data
    }
}
