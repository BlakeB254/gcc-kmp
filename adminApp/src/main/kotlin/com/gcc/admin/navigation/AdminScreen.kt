package com.gcc.admin.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class AdminScreen {
    @Serializable data object Login : AdminScreen()
    @Serializable data object Dashboard : AdminScreen()
    @Serializable data object WorkerList : AdminScreen()
    @Serializable data class WorkerDetail(val workerId: Int) : AdminScreen()
    @Serializable data object SponsorList : AdminScreen()
    @Serializable data class SponsorDetail(val sponsorId: Int) : AdminScreen()
    @Serializable data object JobList : AdminScreen()
    @Serializable data class JobDetail(val jobId: Int) : AdminScreen()
    @Serializable data object RouteList : AdminScreen()
    @Serializable data class RouteEditor(val routeId: Int? = null) : AdminScreen()
    @Serializable data object BlockList : AdminScreen()
    @Serializable data object BountyList : AdminScreen()
    @Serializable data class BountyDetail(val bountyId: Int) : AdminScreen()
    @Serializable data object ServiceAreaMap : AdminScreen()
    @Serializable data object DisputeList : AdminScreen()
    @Serializable data class DisputeDetail(val disputeId: Int) : AdminScreen()
    @Serializable data object Analytics : AdminScreen()
    @Serializable data object FeedbackList : AdminScreen()
    @Serializable data object AddressChanges : AdminScreen()
    @Serializable data object Settings : AdminScreen()
    @Serializable data object LiveTracking : AdminScreen()
}
