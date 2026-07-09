package com.gcc.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.shared.model.Route
import com.gcc.shared.ui.drawPhotoPointScene

/**
 * Route-level thumbnail showing either the route's cover image (if set via admin)
 * or a procedural start scene as a visual preview.
 *
 * Used in route list items and map overlay chips to give
 * both iOS and Android a consistent visual for each route.
 */
@Composable
fun RouteThumbnail(
    route: Route,
    modifier: Modifier = Modifier,
    decodeWidth: Int = 512,
    decodeHeight: Int = 512,
) {
    val imageUrl = route.imageUrl?.takeIf { it.isNotBlank() }
    if (imageUrl != null) {
        NetworkImage(
            url = imageUrl,
            contentDescription = route.displayName,
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
        )
    } else {
        Box(
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPhotoPointScene()
            }
        }
    }
}
