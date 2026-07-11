package com.gcc.app.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun createRootComponent(): RootComponent {
        val lifecycle = LifecycleRegistry()
        var root: RootComponent? = null
        SwingUtilities.invokeAndWait {
            root = RootComponent(
                componentContext = DefaultComponentContext(lifecycle = lifecycle),
            )
        }
        return checkNotNull(root)
    }
}
