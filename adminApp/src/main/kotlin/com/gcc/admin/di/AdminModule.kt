package com.gcc.admin.di

import com.gcc.admin.viewmodel.*
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin DI module for the admin desktop app.
 *
 * Provides:
 * - GccApiClient configured with admin token
 * - TokenManager for persisting admin JWT
 * - All admin ViewModels as singletons (desktop app has one instance of each)
 */
val adminModule = module {
    // Infrastructure
    single { TokenManager() }
    single {
        val tokenManager = get<TokenManager>()
        GccApiClient(
            baseUrl = "http://localhost:7310",
            tokenProvider = { tokenManager.getToken() }
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
    singleOf(::ServiceAreaViewModel)
    singleOf(::DisputeViewModel)
    singleOf(::AnalyticsViewModel)
    singleOf(::FeedbackViewModel)
    singleOf(::AddressChangeViewModel)
}
