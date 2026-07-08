package com.gcc.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GccCard(
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    elevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        // The accent stripe is painted in the draw phase (drawBehind) rather than
        // laid out as a fillMaxHeight sibling inside an IntrinsicSize.Min Row. The
        // old approach forced an intrinsic-height measurement of the content, which
        // CRASHES when the content holds a SubcomposeLayout — lazy lists,
        // BoxWithConstraints, GccMap (e.g. the scan-summary coverage map). Painting
        // in the draw phase needs no intrinsic pass, so any content is safe.
        val stripeWidth = 4.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (accentColor != null) {
                        Modifier.drawBehind {
                            drawRect(
                                color = accentColor,
                                topLeft = Offset.Zero,
                                size = Size(stripeWidth.toPx(), size.height),
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(
                    start = if (accentColor != null) 16.dp + stripeWidth else 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
            content = content,
        )
    }
}
