package com.gcc.app.ui.screens

import androidx.compose.runtime.Composable

/**
 * Post-registration explainer. Shares the intro carousel's slide content
 * (IntroScreen.kt — Scan / Claim your block / Earn / See the change) so
 * there is one carousel implementation; completes into
 * RootComponent.onOnboardingComplete.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit = {},
    onBack: () -> Unit = onComplete,
) {
    IntroCarousel(
        onComplete = onComplete,
        completeLabel = "Get Started",
        onBack = onBack,
    )
}
