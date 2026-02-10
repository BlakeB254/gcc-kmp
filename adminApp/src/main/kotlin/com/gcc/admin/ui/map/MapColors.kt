package com.gcc.admin.ui.map

import androidx.compose.ui.graphics.Color

// -- Block Status Colors --
val MapBlockAvailable = Color(0x553B82F6)
val MapBlockFunded = Color(0x5522C55E)
val MapBlockInProgress = Color(0x55F97316)
val MapBlockCompleted = Color(0x556B7280)
val MapBlockBorder = Color(0x996B7280)

// -- Service Area --
val MapServiceAreaBorder = Color(0xFFF97316)
val MapServiceAreaFill = Color(0x1AF97316)

// -- Community Area --
val MapCommunityStroke = Color(0x664ADE80)
val MapCommunityFill = Color(0x0D22C55E)
val MapCommunityLabel = Color(0xCC9CA3AF)

// -- Selection --
val MapSelectionOutline = Color(0xFF22C55E)
val MapHoverFill = Color(0x2622C55E)

// -- Routes --
val MapRouteLine = Color(0xFF22C55E)
val MapCheckpointDot = Color(0xFFF59E0B)

// -- Spot / Incident Reports --
val MapSpotMarker = Color(0xFFEF4444)
val MapSpotRadius = Color(0x33EF4444)
val MapSpotVerified = Color(0xFF14B8A6)

// -- Bounty Zones --
val MapBountyZone = Color(0xFFF97316)
val MapBountyRadius = Color(0x33F97316)
val MapBountyClaimed = Color(0xFF3B82F6)

// -- Grid --
val MapGridLine = Color(0x1A6B7280)

// -- Map UI --
val MapDarkBackground = Color(0xFF1A1B2E)
val MapControlBg = Color(0xFF252640)
val MapControlIcon = Color(0xFFE5E7EB)

// -- Zoom Constants --
const val ZOOM_COUNTRY = 5.0
const val ZOOM_STATE = 7.0
const val ZOOM_CITY = 11.0
const val ZOOM_NEIGHBORHOOD = 14.0
const val ZOOM_ROUTE = 15.0
const val ZOOM_STREET = 19.0

// -- Chicago Center --
const val CHICAGO_CENTER_LAT = 41.8603
const val CHICAGO_CENTER_LNG = -87.7228
