package com.gcc.admin.di

import com.gcc.admin.viewmodel.AddressChangeViewModel
import com.gcc.admin.viewmodel.AdminDashboardViewModel
import com.gcc.admin.viewmodel.AdminLoginViewModel
import com.gcc.admin.viewmodel.AnalyticsViewModel
import com.gcc.admin.viewmodel.BlockManagementViewModel
import com.gcc.admin.viewmodel.BountyAnalyticsViewModel
import com.gcc.admin.viewmodel.BountyManagementViewModel
import com.gcc.admin.viewmodel.DisputeViewModel
import com.gcc.admin.viewmodel.FeedbackViewModel
import com.gcc.admin.viewmodel.JobManagementViewModel
import com.gcc.admin.viewmodel.LiveTrackingViewModel
import com.gcc.admin.viewmodel.RouteManagementViewModel
import com.gcc.admin.viewmodel.ServiceAreaViewModel
import com.gcc.admin.viewmodel.SponsorManagementViewModel
import com.gcc.admin.viewmodel.WorkerManagementViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin DI module for the admin desktop app (production mode).
 *
 * Provides:
 * - [TokenManager] for persisting admin JWT
 * - [GccApiClient] configured to talk to the GCC server
 * - All admin ViewModels as singletons (desktop app keeps one instance of each)
 */
val adminModule = module {
    // Infrastructure
    single { TokenManager() }
    single {
        val tokenManager = get<TokenManager>()
        GccApiClient(
            baseUrl = "http://localhost:7310",
            tokenProvider = { tokenManager.getToken() },
        )
    }

    // ViewModels
    singleOf(::AdminLoginViewModel)
    singleOf(::AdminDashboardViewModel)
    singleOf(::WorkerManagementViewModel)
    singleOf(::SponsorManagementViewModel)
    singleOf(::JobManagementViewModel)
    singleOf(::RouteManagementViewModel)
    singleOf(::BlockManagementViewModel)
    singleOf(::BountyManagementViewModel)
    singleOf(::BountyAnalyticsViewModel)
    singleOf(::DisputeViewModel)
    singleOf(::AnalyticsViewModel)
    singleOf(::ServiceAreaViewModel)
    singleOf(::FeedbackViewModel)
    singleOf(::AddressChangeViewModel)
    singleOf(::LiveTrackingViewModel)
}
