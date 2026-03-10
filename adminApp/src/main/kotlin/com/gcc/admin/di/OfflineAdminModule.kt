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
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.offline.OfflineTokenManager
import com.gcc.shared.offline.createOfflineApiClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin DI module for the admin desktop app (offline mode).
 *
 * Uses [OfflineTokenManager] (in-memory, pre-loaded with offline JWT) and
 * [createOfflineApiClient] (mock HTTP engine with canned responses).
 */
val offlineAdminModule = module {
    // Infrastructure (offline)
    single<TokenManager> { OfflineTokenManager() }
    single { createOfflineApiClient() }

    // ViewModels (same set as production)
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
