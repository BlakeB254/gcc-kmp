package com.gcc.app.di

import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.platform.PushNotificationManager
import com.gcc.app.platform.createSecureStorage
import com.gcc.app.platform.createSqlDriver
import com.gcc.app.platform.BiometricAuth
import com.gcc.app.platform.ShareManager
import com.gcc.app.platform.detectDeviceCapabilities
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
import com.gcc.app.viewmodel.PointsDashboardViewModel
import com.gcc.app.viewmodel.ImpactViewModel
import com.gcc.app.viewmodel.SponsorWallViewModel
import com.gcc.app.viewmodel.DocumentUploadViewModel
import com.gcc.app.viewmodel.AffectedBlocksViewModel
import com.gcc.app.viewmodel.ArticlesViewModel
import com.gcc.shared.api.AffectedAreasRepository
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.AuthTokenProvider
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.cache.RouteCacheStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** Emits true when the server returns 401/403, signalling forced logout. */
object SessionExpiredSignal {
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()
    fun fire() { _expired.value = true }
    fun reset() { _expired.value = false }
}

val appModule = module {
    single { TokenManager(settings = createSecureStorage()) }
    single { LocationProvider() }
    single { LocationTrackingController() }
    single { ShareManager() }
    single { PushNotificationManager() }
    single { BiometricAuth() }
    single { OnDeviceAnalyzer() }
    single { detectDeviceCapabilities() }
    single { RouteCacheStore(createSqlDriver()) }
    single {
        val tokenManager = get<TokenManager>()
        lateinit var apiClient: GccApiClient
        val authTokenProvider = AuthTokenProvider(tokenManager) { currentToken -> apiClient.refreshToken(currentToken) }
        apiClient = GccApiClient(
            baseUrl = getApiBaseUrl(),
            tokenProvider = { authTokenProvider.currentToken() },
            onUnauthorized = {
                tokenManager.clearToken()
                SessionExpiredSignal.fire()
            },
        )
        apiClient
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
    singleOf(::PointsDashboardViewModel)
    singleOf(::ImpactViewModel)
    singleOf(::SponsorWallViewModel)
    single { AffectedAreasRepository(get()) }
    singleOf(::AffectedBlocksViewModel)
    singleOf(::ArticlesViewModel)
    factory { RouteScanViewModel(get(), get()) }
    factory { DocumentUploadViewModel(get(), get()) }
}
