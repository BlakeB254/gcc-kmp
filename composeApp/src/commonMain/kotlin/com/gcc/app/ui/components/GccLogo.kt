package com.gcc.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import gcc_kmp.composeapp.generated.resources.Res
import gcc_kmp.composeapp.generated.resources.gcc_logo
import org.jetbrains.compose.resources.painterResource

@Composable
fun GccLogo(
    size: Dp = 48.dp,
    showGlow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val glowModifier = if (showGlow) {
        Modifier.shadow(
            elevation = 16.dp,
            shape = CircleShape,
            ambientColor = GccGreen.copy(alpha = 0.4f),
            spotColor = GccGreen.copy(alpha = 0.3f),
        )
    } else {
        Modifier
    }

    Image(
        painter = painterResource(Res.drawable.gcc_logo),
        contentDescription = "GCC Logo",
        modifier = modifier
            .size(size)
            .then(glowModifier)
            .clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
}
