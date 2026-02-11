package com.gcc.app.ui.theme

import androidx.compose.ui.graphics.Color

// GCC Brand Colors
val GccGreen = Color(0xFF22C55E)
val GccGreenDark = Color(0xFF16A34A)
val GccGreenLight = Color(0xFF86EFAC)

// Neutral
val GccBackground = Color(0xFFFAFAFA)
val GccSurface = Color(0xFFFFFFFF)
val GccOnBackground = Color(0xFF1A1A1A)
val GccOnSurface = Color(0xFF333333)

// Status colors
val GccAvailable = Color(0xFF22C55E)
val GccClaimed = Color(0xFFF59E0B)
val GccInProgress = Color(0xFF3B82F6)
val GccPendingReview = Color(0xFF8B5CF6)
val GccCompleted = Color(0xFF10B981)
val GccError = Color(0xFFEF4444)

// Badge tier colors
val BadgeBronze = Color(0xFFCD7F32)
val BadgeSilver = Color(0xFFC0C0C0)
val BadgeGold = Color(0xFFFFD700)
val BadgePlatinum = Color(0xFFE5E4E2)
val BadgeDiamond = Color(0xFFB9F2FF)

// Map colors
val MapRouteGreen = Color(0xFF22C55E)
val MapRouteGray = Color(0xFF9CA3AF)
val MapCheckpointCompleted = Color(0xFF10B981)
val MapCheckpointCurrent = Color(0xFFF59E0B)
val MapCheckpointUpcoming = Color(0xFF6B7280)
val MapGeofenceRadius = Color(0x3322C55E)
val MapGpsBlue = Color(0xFF3B82F6)

// Gold accent
val GccGold = Color(0xFFF59E0B)
val GccGoldDark = Color(0xFFD97706)
val GccGoldLight = Color(0xFFFDE68A)

// Blue accent
val GccBlue = Color(0xFF3B82F6)
val GccBlueDark = Color(0xFF2563EB)
val GccBlueLight = Color(0xFFBFDBFE)

// Gradient colors
val GradientGreenStart = Color(0xFF22C55E)
val GradientGreenMid = Color(0xFF16A34A)
val GradientGreenEnd = Color(0xFF0F4C2A)
val GradientButtonStart = Color(0xFF22C55E)
val GradientButtonEnd = Color(0xFF16A34A)

// Card accent strip colors
val CardAccentGreen = Color(0xFF22C55E)
val CardAccentGold = Color(0xFFF59E0B)
val CardAccentBlue = Color(0xFF3B82F6)
val CardAccentRed = Color(0xFFEF4444)
val CardAccentPurple = Color(0xFF8B5CF6)

// Chip background colors (10% alpha tints)
val ChipBgAvailable = Color(0x1A22C55E)
val ChipBgClaimed = Color(0x1AF59E0B)
val ChipBgInProgress = Color(0x1A3B82F6)
val ChipBgPendingReview = Color(0x1A8B5CF6)
val ChipBgCompleted = Color(0x1A10B981)
val ChipBgDisputed = Color(0x1AEF4444)

// Surface and text variants
val SurfaceMuted = Color(0xFFF1F5F9)
val TextMuted = Color(0xFF6B7280)
val TextSubtle = Color(0xFF9CA3AF)
val DividerColor = Color(0xFFE5E7EB)

// Enhanced map colors
val MapServiceBoundary = Color(0xFFF97316)
val MapBackground = Color(0xFFF5F5F0)       // neutral warm gray (like real map tiles)
val MapCommunityFill = Color(0x1A22C55E)
val MapCommunityStroke = Color(0xFF4ADE80)

// Street colors for Canvas map rendering
val MapStreetArterial = Color(0xFFFFFFFF)     // white for major roads
val MapStreetCollector = Color(0xFFF8F8F5)    // off-white for minor streets
val MapStreetOutline = Color(0xFFE0DDD5)      // subtle border on streets
val MapBlockFill = Color(0xFFF0EDE5)          // city blocks between streets
val MapParkFill = Color(0xFFD5E8D4)           // parks / green spaces
val MapParkStroke = Color(0xFFB5D6B3)
val MapStreetLabel = Color(0xFF7A7A6E)        // muted text for street names
val MapWaterFill = Color(0xFFB4D4E4)          // water (not used in NL but available)

// Map gradient fills (radial gradient centers for community areas)
val MapAreaGradientCenter = Color(0x3322C55E)
val MapAreaGradientEdge = Color(0x0D22C55E)
val MapServiceGradientCenter = Color(0x4D22C55E)
val MapServiceGradientEdge = Color(0x1A22C55E)

// Map control colors
val MapControlBg = Color(0xFFFFFFFE)
val MapControlIcon = Color(0xFF374151)
val MapScaleBar = Color(0xFF6B7280)

// Community area map colors (non-service areas)
val MapAreaBorder = Color(0xFF6B7280)         // gray border for non-service areas
val MapAreaFill = Color(0x0D6B7280)           // very subtle gray fill
val MapAreaLabel = Color(0xFF4B5563)          // dark gray area labels

// Geographic boundary colors
val MapCommunityBoundary = Color(0xFFDC2626)  // red for community area outlines
val MapCityBoundary = Color(0xFF4B5563)       // dark gray for Chicago city outline
val MapStateBoundary = Color(0xFF9CA3AF)      // lighter gray for Illinois state outline

// Fundable block status colors (20% alpha fills)
val BlockAvailable = Color(0x339CA3AF)        // gray, needs funding
val BlockFunded = Color(0x3322C55E)           // green, fully funded
val BlockInProgress = Color(0x333B82F6)       // blue, work underway
val BlockCompleted = Color(0x33F59E0B)        // gold, done
val BlockBorder = Color(0xFF6B7280)           // subtle border
