package com.gcc.shared.theme

/**
 * Shared design tokens for GCC apps.
 *
 * Values are stored as plain Float/Int/Long so that the shared module does not
 * need a Compose-UI dependency. Each app converts to `Dp` at the call site:
 *
 *     GccSpacing.md.dp   // 13.dp
 */

// Fibonacci-based spacing system (values in dp)
object GccSpacing {
    const val xxs = 3f
    const val xs = 5f
    const val sm = 8f
    const val md = 13f
    const val lg = 21f
    const val xl = 34f
    const val xxl = 55f
    const val xxxl = 89f
}

// Fibonacci-based corner radii (values in dp)
object GccShapes {
    const val xs = 3f
    const val sm = 5f
    const val md = 8f
    const val lg = 13f
    const val xl = 21f
}

// Standard dimensions using golden ratio (values in dp unless noted)
object GccDimensions {
    const val phi = 1.618034f
    // Icons
    const val iconSm = 21f
    const val iconMd = 34f
    const val iconLg = 55f
    // Avatars
    const val avatarSm = 34f
    const val avatarMd = 55f
    const val avatarLg = 89f
    // Cards
    const val cardMinHeight = 89f
    const val cardRadius = 13f
    // Buttons
    const val buttonHeight = 55f
    const val buttonRadiusSmall = 8f
    const val buttonRadiusLarge = 13f
    // Touch targets
    const val touchTarget = 55f
}

// Fibonacci-based elevation scale (values in dp)
object GccElevation {
    const val none = 0f
    const val subtle = 1f
    const val card = 2f
    const val raised = 3f
    const val floating = 5f
    const val overlay = 8f
    const val modal = 13f
}

// Brand colors as Long values (used by both platforms)
object GccColors {
    const val Green = 0xFF16763A
    const val GreenDark = 0xFF0B3B1D
    const val Blue = 0xFF155B92
    const val Sky = 0xFF30A0CE
    const val Error = 0xFFEF4444
    const val Emerald = 0xFF10B981

    // Status colors
    const val StatusAvailable = 0xFF16763A
    const val StatusInProgress = 0xFF155B92
    const val StatusCompleted = 0xFF10B981  // emerald (standardized)
    const val StatusCancelled = 0xFFEF4444
    const val StatusWarning = 0xFFF59E0B

    // Bounty colors
    const val BountyOpen = 0xFFEF4444
    const val BountyAmount = 0xFF16763A
    const val BountyClaimed = 0xFF155B92
    const val BountyCompleted = 0xFF10B981
    const val SpotReport = 0xFFF97316
    const val SpotterReward = 0xFF30A0CE
    const val SecretBounty = 0xFF8B5CF6
}
