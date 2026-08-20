package com.gcc.app.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.platform.FirstRunStore
import com.gcc.shared.offline.MemorySettings
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootComponentTest {

    @Test
    fun repeatedDocumentUploadNavigationFromProfileDoesNotDuplicateTheStack() {
        val root = createRootComponent()

        root.onLoginSuccess()
        root.switchToTab(Screen.Profile)
        root.navigateToDocumentUpload()
        root.navigateToDocumentUpload()

        val stack = root.childStack.value
        assertEquals(Screen.DocumentUpload, stack.active.configuration)
        assertEquals(listOf(Screen.Profile), stack.backStack.map { it.configuration })
    }

    @Test
    fun firstLaunchWithoutIntroSeenStartsOnIntro() {
        val root = createRootComponent(firstRunStore = FirstRunStore(MemorySettings()))

        assertEquals(Screen.Intro, root.childStack.value.active.configuration)
    }

    @Test
    fun introSeenFallsThroughToLoginWhenLoggedOut() {
        val store = FirstRunStore(MemorySettings()).apply { introSeen = true }
        val root = createRootComponent(firstRunStore = store)

        assertEquals(Screen.Login, root.childStack.value.active.configuration)
    }

    @Test
    fun introCompleteMarksFlagAndRoutesToLoginWhenLoggedOut() {
        val store = FirstRunStore(MemorySettings())
        val root = createRootComponent(firstRunStore = store)

        root.onIntroComplete()

        assertTrue(store.introSeen)
        assertEquals(Screen.Login, root.childStack.value.active.configuration)
    }

    @Test
    fun loginSuccessDetoursThroughIntroWhenNotSeen() {
        val store = FirstRunStore(MemorySettings()).apply { introSeen = true }
        val root = createRootComponent(firstRunStore = store)
        // Simulate a sign-in on a device where the intro was never completed:
        // Login → Intro → (Get started) → Login-flow destination.
        store.introSeen = false
        root.onLoginSuccess()
        assertEquals(Screen.Intro, root.childStack.value.active.configuration)

        root.onIntroComplete()
        assertTrue(store.introSeen)
    }

    private fun createRootComponent(
        firstRunStore: FirstRunStore? = null,
    ): RootComponent {
        val lifecycle = LifecycleRegistry()
        var root: RootComponent? = null
        SwingUtilities.invokeAndWait {
            root = RootComponent(
                componentContext = DefaultComponentContext(lifecycle = lifecycle),
                firstRunStore = firstRunStore,
            )
        }
        return checkNotNull(root)
    }
}
