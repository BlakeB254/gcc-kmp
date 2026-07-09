package com.gcc.app.di

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
}
