package com.gcc.shared.api

import com.gcc.shared.model.*
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
    private val tokenProvider: suspend () -> String? = { null },
    httpClientOverride: HttpClient? = null,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    val httpClient = httpClientOverride ?: HttpClient {
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

    suspend fun getFeed(
        limit: Int = 30,
        offset: Int = 0,
        type: String? = null,
    ): List<FeedItem> {
        val response = httpClient.get("$baseUrl/api/feed") {
            parameter("limit", limit)
            parameter("offset", offset)
            type?.let { parameter("type", it) }
        }
        return response.body<ApiResponse<List<FeedItem>>>().data
    }

    suspend fun getMyBadges(): List<Badge> {
        val response = httpClient.get("$baseUrl/api/gamification/me/badges")
        return response.body<ApiResponse<List<Badge>>>().data
    }

    suspend fun getMyServiceArea(): ServiceArea? {
        return try {
            val response = httpClient.get("$baseUrl/api/workers/me/service-area")
            response.body<ApiResponse<ServiceArea>>().data
        } catch (_: Exception) {
            null
        }
    }
}
