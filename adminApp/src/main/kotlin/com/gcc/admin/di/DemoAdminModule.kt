package com.gcc.admin.di

import com.gcc.admin.ui.map.AdminMapViewModel
import com.gcc.admin.viewmodel.*
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.demo.DemoTokenManager
import com.gcc.shared.demo.createDemoApiClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val demoAdminModule = module {
    single<TokenManager> { DemoTokenManager() }
    single { createDemoApiClient() }

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
    singleOf(::AdminMapViewModel)
}
