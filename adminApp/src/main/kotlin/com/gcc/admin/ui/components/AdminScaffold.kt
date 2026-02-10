@file:Suppress("unused")

package com.gcc.admin.ui.components

/**
 * Re-export from the canonical location so existing screen imports
 * (`com.gcc.admin.ui.components.AdminScaffold`) continue to compile.
 *
 * The canonical implementation lives at [com.gcc.admin.ui.layout.AdminScaffold].
 */
import com.gcc.admin.ui.layout.AdminScaffold as AdminScaffoldImpl

// Type alias so callers with `import com.gcc.admin.ui.components.AdminScaffold` still resolve.
// Since Compose functions cannot be type-aliased, we provide a wrapper.
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable

@Composable
fun AdminScaffold(
    title: String,
    subtitle: String? = null,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    AdminScaffoldImpl(
        title = title,
        subtitle = subtitle,
        scrollable = scrollable,
        actions = actions,
        content = content,
    )
}
