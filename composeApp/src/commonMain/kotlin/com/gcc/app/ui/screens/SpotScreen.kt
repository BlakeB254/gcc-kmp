package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CameraPreview
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.SpotPhase
import com.gcc.app.viewmodel.SpotViewModel
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SpotScreen(
    onBack: () -> Unit = {},
    onVolunteerCleanup: (reportId: Int) -> Unit = {},
    onNavigateToActiveJob: (jobId: Int, routeId: Int) -> Unit = { _, _ -> },
) {
    val viewModel = koinInject<SpotViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfaceMuted),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "\u2190",
                            style = MaterialTheme.typography.titleMedium,
                            color = GccGreenDark,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (uiState.phase) {
                        SpotPhase.CAMERA -> "Take Photo"
                        SpotPhase.LOCATION -> "Confirm Location"
                        SpotPhase.DESCRIPTION -> "Add Details"
                        SpotPhase.SUBMITTING -> "Submitting..."
                        SpotPhase.RESULT -> "Report Submitted"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (uiState.phase) {
                SpotPhase.CAMERA -> CameraPhase(
                    photoCount = uiState.capturedPhotoUrls.size,
                    onCapture = { viewModel.addPhoto("photo_${uiState.capturedPhotoUrls.size + 1}.jpg") },
                    onNext = { viewModel.nextPhase() },
                )

                SpotPhase.LOCATION -> LocationPhase(
                    lat = uiState.currentLat,
                    lng = uiState.currentLng,
                    onNext = { viewModel.nextPhase() },
                )

                SpotPhase.DESCRIPTION -> DescriptionPhase(
                    description = uiState.description,
                    onDescriptionChange = { viewModel.setDescription(it) },
                    photoCount = uiState.capturedPhotoUrls.size,
                    error = uiState.error,
                    onSubmit = { viewModel.nextPhase() },
                )

                SpotPhase.SUBMITTING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GccGreen)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Analyzing and submitting...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextMuted,
                            )
                        }
                    }
                }

                SpotPhase.RESULT -> ResultPhase(
                    report = uiState.submittedReport,
                    matchingBounties = uiState.matchingBounties,
                    error = uiState.error,
                    onAcceptBounty = { viewModel.acceptBounty(it) },
                    onVolunteerCleanup = {
                        uiState.submittedReport?.let { report ->
                            onVolunteerCleanup(report.id)
                        }
                    },
                    onNewReport = { viewModel.reset() },
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun CameraPhase(
    photoCount: Int,
    onCapture: () -> Unit,
    onNext: () -> Unit,
) {
    val cameraManager = remember { CameraManager() }
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Live camera preview
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CameraPreview(modifier = Modifier.fillMaxSize())

            // Photo count overlay
            if (photoCount > 0) {
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "$photoCount photo${if (photoCount != 1) "s" else ""} captured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GccGreen,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Capture button — triggers native camera (UIImagePickerController on iOS)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    if (!isCapturing) {
                        isCapturing = true
                        coroutineScope.launch {
                            val photo = cameraManager.capturePhoto()
                            if (photo != null) {
                                onCapture()
                            }
                            isCapturing = false
                        }
                    }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                enabled = !isCapturing,
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = GccGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "\uD83D\uDCF8",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GccButton(
            text = if (photoCount > 0) "Next: Set Location" else "Skip Photo",
            onClick = onNext,
            variant = if (photoCount > 0) GccButtonVariant.PRIMARY else GccButtonVariant.OUTLINED,
        )
    }
}

@Composable
private fun LocationPhase(
    lat: Double,
    lng: Double,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            "Pin Location",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Confirm where you spotted the issue.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Map with location pin
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            GccMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = lat,
                centerLng = lng,
                zoom = MapStyles.ZOOM_ROUTE,
                currentLocation = Coordinates(lng = lng, lat = lat),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Coordinates display
        GccCard(accentColor = CardAccentBlue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Lat: $lat",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Text(
                    "Lng: $lng",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GccButton(
            text = "Next: Add Details",
            onClick = onNext,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}

@Composable
private fun DescriptionPhase(
    description: String,
    onDescriptionChange: (String) -> Unit,
    photoCount: Int,
    error: String?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            "Describe What You See",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Optional: Add a description to help others.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            placeholder = {
                Text(
                    "e.g. Dumped tires near alley entrance...",
                    color = TextSubtle,
                )
            },
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GccGreen,
                cursorColor = GccGreen,
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Summary row
        GccCard(accentColor = CardAccentGreen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "$photoCount photo${if (photoCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (description.isNotBlank()) "Description added" else "No description",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (description.isNotBlank()) GccGreen else TextSubtle,
                )
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
        }

        Spacer(modifier = Modifier.weight(1f))

        GccButton(
            text = "Submit Report",
            onClick = onSubmit,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}

@Composable
private fun ResultPhase(
    report: com.gcc.shared.model.CommunityReport?,
    matchingBounties: List<Bounty>,
    error: String?,
    onAcceptBounty: (Int) -> Unit,
    onVolunteerCleanup: () -> Unit,
    onNewReport: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Success card
        item {
            GccCard(accentColor = CardAccentGreen) {
                Text(
                    "Report Created",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = GccGreen,
                )
                Spacer(modifier = Modifier.height(4.dp))
                report?.let {
                    Text(
                        "Report #${it.id} submitted successfully.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                    it.description?.let { desc ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle,
                        )
                    }
                    it.aiAnalysis?.let { analysis ->
                        Spacer(modifier = Modifier.height(8.dp))
                        AiAnalysisCard(analysis)
                    }
                }
            }
        }

        // Points awarded
        item {
            report?.let {
                if (it.observerRewardPoints > 0) {
                    GccCard(accentColor = CardAccentGold) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "Observer Reward",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    "Thanks for spotting this!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            Text(
                                "+${it.observerRewardPoints} pts",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = GccGold,
                            )
                        }
                    }
                }
            }
        }

        // Matching bounties
        if (matchingBounties.isNotEmpty()) {
            item {
                SectionHeader(title = "Matching Bounties")
            }

            items(matchingBounties, key = { it.id }) { bounty ->
                BountyMatchCard(
                    bounty = bounty,
                    onAccept = { onAcceptBounty(bounty.id) },
                )
            }
        }

        // Volunteer cleanup option
        item {
            report?.let {
                GccCard(accentColor = CardAccentBlue) {
                    Text(
                        "Volunteer to Clean",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Start a volunteer cleanup at this spot to earn points and make an impact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GccButton(
                        text = "Start Volunteer Cleanup",
                        onClick = onVolunteerCleanup,
                        variant = GccButtonVariant.SECONDARY,
                    )
                }
            }
        }

        error?.let {
            item {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
            }
        }

        // Action buttons
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GccButton(
                    text = "Spot Another",
                    onClick = onNewReport,
                    variant = GccButtonVariant.OUTLINED,
                )
                GccButton(
                    text = "Back to Dashboard",
                    onClick = onBack,
                    variant = GccButtonVariant.PRIMARY,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun AiAnalysisCard(analysis: com.gcc.shared.model.AiAnalysis) {
    Column {
        Text(
            "AI Analysis",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = CardAccentPurple,
        )
        Spacer(modifier = Modifier.height(4.dp))
        analysis.severity?.let {
            Text(
                "Severity: $it",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        analysis.estimatedWeightLbs?.let {
            Text(
                "Est. Weight: ${it.toInt()} lbs",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        if (analysis.trashTypes.isNotEmpty()) {
            Text(
                "Types: ${analysis.trashTypes.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun BountyMatchCard(
    bounty: Bounty,
    onAccept: () -> Unit,
) {
    GccCard(accentColor = CardAccentGold) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    bounty.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                bounty.description?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                    )
                }
                bounty.communityArea?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$${bounty.amount / 100}",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccGold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onAccept) {
                    Text("Accept", color = GccGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
