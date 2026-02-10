package com.gcc.app.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class Screen {
    @Serializable
    data object Login : Screen()

    @Serializable
    data object Register : Screen()

    @Serializable
    data object Onboarding : Screen()

    @Serializable
    data object Dashboard : Screen()

    @Serializable
    data class RouteDetail(val routeId: Int) : Screen()

    @Serializable
    data class ActiveJob(val jobId: Int, val routeId: Int) : Screen()

    @Serializable
    data object Profile : Screen()

    @Serializable
    data object Achievements : Screen()

    @Serializable
    data object MapFullScreen : Screen()

    @Serializable
    data object Settings : Screen()

    @Serializable
    data class Legal(val type: String) : Screen()

    @Serializable
    data object Spot : Screen()

    @Serializable
    data class VolunteerCleanup(val reportId: Int) : Screen()

    @Serializable
    data object BountyList : Screen()

    @Serializable
    data class BountyDetail(val bountyId: Int) : Screen()

    @Serializable
    data object Wallet : Screen()

    @Serializable
    data object Leaderboard : Screen()
}
