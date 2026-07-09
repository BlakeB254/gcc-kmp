package com.gcc.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccGreenDark
import com.gcc.app.ui.theme.TextMuted

sealed class RouteAuthState {
    data object Authorized : RouteAuthState()
    data object RouteUnavailable : RouteAuthState()
    data object VerificationRequired : RouteAuthState()
    data class OutsideServiceArea(val workerArea: String?, val routeArea: String?) : RouteAuthState()
    data object Unknown : RouteAuthState()
}

@Composable
fun RouteAuthorizationBanner(
    state: RouteAuthState,
    onVerifyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amber = Color(0xFFB7791F)
    val (accentColor, backgroundColor, title, body) = when (state) {
        RouteAuthState.Authorized -> BannerContent(
            accentColor = GccGreen,
            backgroundColor = GccGreen.copy(alpha = 0.10f),
            title = "You can work this route",
            body = "Your verification and service area match this block.",
        )
        RouteAuthState.RouteUnavailable -> BannerContent(
            accentColor = amber,
            backgroundColor = amber.copy(alpha = 0.12f),
            title = "Not available yet",
            body = "An admin must publish this route and set a block boundary or route path before workers can start it.",
        )
        RouteAuthState.VerificationRequired -> BannerContent(
            accentColor = amber,
            backgroundColor = amber.copy(alpha = 0.12f),
            title = "Verification required",
            body = "Verify yourself before claiming or starting this route.",
        )
        is RouteAuthState.OutsideServiceArea -> BannerContent(
            accentColor = GccError,
            backgroundColor = GccError.copy(alpha = 0.10f),
            title = "Outside your service area",
            body = outsideServiceAreaCopy(state.workerArea, state.routeArea),
        )
        RouteAuthState.Unknown -> BannerContent(
            accentColor = GccGreen,
            backgroundColor = GccGreen.copy(alpha = 0.10f),
            title = "You can work this route",
            body = "We could not confirm this route's service area, so claiming stays available.",
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(
                modifier = Modifier
                    .width(4.dp)
                    .height(42.dp)
                    .background(accentColor)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = accentColor,
                    )
                    if (state == RouteAuthState.Unknown) {
                        ZoneUnknownChip()
                    }
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                if (state == RouteAuthState.VerificationRequired) {
                    OutlinedButton(
                        onClick = onVerifyClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = amber),
                        border = BorderStroke(1.dp, amber.copy(alpha = 0.45f)),
                    ) {
                        Text("Verify yourself", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private data class BannerContent(
    val accentColor: Color,
    val backgroundColor: Color,
    val title: String,
    val body: String,
)

@Composable
private fun ZoneUnknownChip() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = GccGreen.copy(alpha = 0.14f),
    ) {
        Text(
            text = "Zone match unknown",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = GccGreenDark,
        )
    }
}

private fun outsideServiceAreaCopy(workerArea: String?, routeArea: String?): String {
    val routeText = routeArea ?: "this route's community area"
    val workerText = workerArea ?: "your saved neighborhood"
    return "Your service area is $workerText, but this route is in $routeText."
}
