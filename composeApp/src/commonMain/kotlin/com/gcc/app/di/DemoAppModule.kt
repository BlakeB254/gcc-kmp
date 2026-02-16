package com.gcc.app.di

import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.ShareManager
import com.gcc.app.viewmodel.ActiveJobViewModel
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.app.viewmodel.FeedViewModel
import com.gcc.app.viewmodel.LeaderboardViewModel
import com.gcc.app.viewmodel.LoginViewModel
import com.gcc.app.viewmodel.ProfileViewModel
import com.gcc.app.viewmodel.RegisterViewModel
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.viewmodel.SettingsViewModel
import com.gcc.app.viewmodel.SpotViewModel
import com.gcc.app.viewmodel.VolunteerCleanupViewModel
import com.gcc.app.viewmodel.WalletViewModel
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.demo.DemoTokenManager
import com.gcc.shared.demo.createDemoApiClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val demoAppModule = module {
    single<TokenManager> { DemoTokenManager() }
    single { LocationProvider() }
    single { ShareManager() }
    single { createDemoApiClient() }
    singleOf(::LoginViewModel)
    singleOf(::RegisterViewModel)
    singleOf(::DashboardViewModel)
    singleOf(::ActiveJobViewModel)
    singleOf(::ProfileViewModel)
    singleOf(::RouteDetailViewModel)
    singleOf(::SettingsViewModel)
    singleOf(::SpotViewModel)
    singleOf(::VolunteerCleanupViewModel)
    singleOf(::BountyViewModel)
    singleOf(::WalletViewModel)
    singleOf(::LeaderboardViewModel)
    singleOf(::FeedViewModel)
}
