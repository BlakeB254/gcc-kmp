package com.gcc.admin.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gcc.admin.navigation.AdminScreen
import com.gcc.admin.ui.theme.AdminBackground

/**
 * Root desktop layout: permanent sidebar on the left, content area on the right.
 *
 * The sidebar (240 dp) provides navigation across all top-level screens.
 * The content area fills the remaining width and stacks [AdminTopBar] above
 * the screen-specific content.
 *
 * This scaffold is used for all screens except Login, which renders full-bleed.
 */
@Composable
fun AdminScaffold(
    currentScreen: AdminScreen,
    onNavigate: (AdminScreen) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // ── Sidebar ──────────────────────────────────────────────────────
        AdminSidebar(
            activeScreen = currentScreen,
            onNavigate = onNavigate,
        )

        // ── Content Area ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(AdminBackground),
        ) {
            content()
        }
    }
}
