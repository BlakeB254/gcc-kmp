package com.gcc.app.di

import com.gcc.app.viewmodel.AdminJobListViewModel
import com.gcc.app.viewmodel.AffectedBlocksViewModel
import com.gcc.app.viewmodel.DocumentUploadViewModel
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Koin resolves lazily, so a definition with a missing dependency compiles,
 * loads, and only throws when something first resolves it. [DocumentUploadViewModel]
 * is resolved exactly once in the app — when the user taps "Provide Info" on the
 * profile's verification banner (ProfileScreen.kt) or lands on the onboarding
 * document-upload step (RootComponent.kt). Its `CameraManager` dependency was
 * never registered, so both paths crashed with NoDefinitionFoundException.
 *
 * These tests resolve the graph's lazily-reached leaves eagerly. Only ViewModels
 * whose deps are all constructible off-device belong here — anything touching
 * SQLDelight/ARKit is exercised by its own tests.
 */
class AppModuleResolutionTest {

    @Test
    fun documentUploadViewModelResolvesFromAppModule() {
        val koin = koinApplication { modules(appModule) }.koin
        assertNotNull(koin.get<DocumentUploadViewModel>())
    }

    @Test
    fun documentUploadViewModelResolvesFromOfflineAppModule() {
        val koin = koinApplication { modules(offlineAppModule) }.koin
        assertNotNull(koin.get<DocumentUploadViewModel>())
    }

    /** Resolved only when an admin taps an Operations row, so nothing else
     *  would surface a missing definition until that screen opens. */
    @Test
    fun adminJobListViewModelResolvesFromAppModule() {
        val koin = koinApplication { modules(appModule) }.koin
        assertNotNull(koin.get<AdminJobListViewModel>())
    }

    @Test
    fun adminJobListViewModelResolvesFromOfflineAppModule() {
        val koin = koinApplication { modules(offlineAppModule) }.koin
        assertNotNull(koin.get<AdminJobListViewModel>())
    }

    /** MapFullScreen injects this on arrival, and the dashboard-map tap
     *  navigates there — so in demo/offline builds (forced on every
     *  debuggable APK) an unregistered definition crashed the app on the
     *  tap, reading as "the dot and route disappear" (Bug 3, build 188). */
    @Test
    fun affectedBlocksViewModelResolvesFromAppModule() {
        val koin = koinApplication { modules(appModule) }.koin
        assertNotNull(koin.get<AffectedBlocksViewModel>())
    }

    @Test
    fun affectedBlocksViewModelResolvesFromOfflineAppModule() {
        val koin = koinApplication { modules(offlineAppModule) }.koin
        assertNotNull(koin.get<AffectedBlocksViewModel>())
    }
}
