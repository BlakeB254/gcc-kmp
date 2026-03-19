package com.gcc.app.di

import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.PushNotificationManager
import com.gcc.app.platform.SecureStorage
import com.gcc.app.platform.BiometricAuth
import com.gcc.app.platform.ShareManager
import com.gcc.app.platform.getApiBaseUrl
import com.gcc.app.viewmodel.ActiveJobViewModel
import com.gcc.app.viewmodel.BiometricAuthViewModel
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.app.viewmodel.LoginViewModel
import com.gcc.app.viewmodel.ProfileViewModel
import com.gcc.app.viewmodel.RegisterViewModel
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.viewmodel.SettingsViewModel
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.viewmodel.LeaderboardViewModel
import com.gcc.app.viewmodel.SpotViewModel
import com.gcc.app.viewmodel.VolunteerCleanupViewModel
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.app.viewmodel.FeedViewModel
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.app.viewmodel.SpotVerificationViewModel
import com.gcc.app.viewmodel.WalletViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    single { TokenManager(settings = SecureStorage()) }
    single { LocationProvider() }
    single { LocationTrackingController() }
    single { ShareManager() }
    single { PushNotificationManager() }
    single { BiometricAuth() }
    single { OnDeviceAnalyzer() }
    single {
        val tokenManager = get<TokenManager>()
        GccApiClient(
            baseUrl = getApiBaseUrl(),
            tokenProvider = { tokenManager.getToken() },
        )
    }
    singleOf(::BiometricAuthViewModel)
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
    singleOf(::AdminPanelViewModel)
    singleOf(::SpotVerificationViewModel)
    factory { RouteScanViewModel(get(), get()) }
}
