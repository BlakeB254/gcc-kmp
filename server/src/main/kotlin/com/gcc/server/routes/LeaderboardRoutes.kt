package com.gcc.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val workerId: Int,
    val name: String,
    val jobsCompleted: Int,
    val totalEarnings: Int, // cents
    val averageRating: Double
)

fun Routing.leaderboardRoutes() {
    route("/api/leaderboard") {
        get {
            // TODO: Replace with database query once Exposed tables are wired up
            val mockLeaderboard = listOf(
                LeaderboardEntry(1, 1, "Marcus J.", 47, 470000, 4.9),
                LeaderboardEntry(2, 2, "Terrence W.", 42, 420000, 4.8),
                LeaderboardEntry(3, 3, "DeShawn C.", 38, 380000, 4.7),
                LeaderboardEntry(4, 4, "Andre M.", 35, 350000, 4.6),
                LeaderboardEntry(5, 5, "Jamal R.", 31, 310000, 4.5)
            )
            call.respond(HttpStatusCode.OK, mockLeaderboard)
        }
    }
}
