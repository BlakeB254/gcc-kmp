package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextSubtle

@Composable
fun NetworkImage(
    url: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        contentScale = contentScale,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = GccGreen,
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No image",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle,
                )
            }
        },
    )
}

@Composable
fun PhotoStrip(
    urls: List<String>,
    modifier: Modifier = Modifier,
    onPhotoClick: ((String) -> Unit)? = null,
) {
    if (urls.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(urls) { url ->
            NetworkImage(
                url = url,
                contentDescription = "Photo",
                modifier = Modifier
                    .size(80.dp)
                    .then(
                        if (onPhotoClick != null) Modifier.clickable { onPhotoClick(url) }
                        else Modifier
                    ),
            )
        }
    }
}
