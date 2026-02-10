package com.gcc.admin.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminBackground
import com.gcc.admin.ui.theme.AdminOutlineVariant

/**
 * Standard scaffold for admin content pages.
 *
 * Provides a consistent layout of [AdminTopBar] at the top with a
 * separator, followed by content on the admin dark background.
 *
 * When [scrollable] is true (default), the content area is wrapped in
 * a vertical scroll modifier. Set to false for screens that manage
 * their own scrolling (e.g. those using a LazyColumn inside DataTable).
 *
 * This does NOT include the sidebar -- the sidebar is rendered at the
 * root level in [com.gcc.admin.AdminApp].
 */
@Composable
fun AdminScaffold(
    title: String,
    subtitle: String? = null,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AdminBackground),
    ) {
        AdminTopBar(
            title = title,
            subtitle = subtitle,
            actions = actions,
        )

        HorizontalDivider(
            color = AdminOutlineVariant,
            thickness = 1.dp,
        )

        if (scrollable) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                content()
            }
        }
    }
}
