package com.gcc.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.gcc.app.ui.theme.GccTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Renders a flat, single-color icon glyph (transparent-background PNG,
 * generated art) tinted to the given accent color. Used for the dashboard
 * quick-action row and every FAB that reuses one of those glyphs, so the
 * color always tracks the button/surface it sits in rather than being baked
 * into the asset.
 */
@Composable
fun QuickActionGlyph(
    resource: DrawableResource,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(GccTheme.dimensions.iconMd),
    )
}
