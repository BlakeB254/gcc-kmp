package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.authoredInnerRings
import com.gcc.app.ui.theme.*
import com.gcc.app.util.fmt
import com.gcc.app.viewmodel.ActiveJobViewModel
import com.gcc.shared.geo.routePathCoordinates
import com.gcc.shared.geo.routeStartCoordinates
import com.gcc.shared.model.Job
import com.gcc.shared.model.JobStatus
import com.gcc.shared.model.isPayoutDenied
import com.gcc.shared.model.TEST_ASSIGNMENT_START_WINDOW_MINUTES
import com.gcc.shared.model.isTestAssignment
import com.gcc.shared.model.testAssignmentStartDeadlineMillis
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

/**
 * Assignment gate for an accepted job.
 *
 * Pre-start: GPS-verified "go to the route boundary" gate with the test-
 * assignment countdown and decline option. Once the job starts, the worker is
 * routed straight into the two-lap route scan (Scout & Clean) via
 * [onStartScan] — there is no separate stop flow; the scan IS the job.
 */
@Composable
fun ActiveJobScreen(
    jobId: Int = 0,
    routeId: Int = 0,
    onBack: () -> Unit = {},
    onStartScan: (Int) -> Unit = {},
) {
    val viewModel = koinInject<ActiveJobViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    var nowMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }

    // Auto-open the two-lap scan exactly once per visit to this screen: when
    // the worker taps "Start Route", or when they arrive with a job that is
    // already IN_PROGRESS. Saveable so returning (back) from the scan shows
    // the in-progress panel instead of re-launching a fresh scan.
    var scanAutoOpened by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(jobId, routeId) {
        viewModel.loadJob(jobId, routeId)
    }

    LaunchedEffect(currentLocation) {
        currentLocation?.let { viewModel.updateLocation(it) }
    }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose {
            locationProvider.stopLocationUpdates()
        }
    }

    LaunchedEffect(uiState.job?.id, uiState.jobStarted) {
        while (uiState.job?.isTestAssignment() == true && uiState.job?.status == JobStatus.CLAIMED) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            delay(1000L)
        }
    }

    // Decline succeeded server-side: pop back HERE, on the composition's
    // main thread. The ViewModel raises a flag rather than calling onBack
    // from its background coroutine, where popping the Decompose stack crashes.
    LaunchedEffect(uiState.declined) {
        if (uiState.declined) {
            viewModel.acknowledgeDeclined()
            onBack()
        }
    }

    LaunchedEffect(uiState.jobStarted, uiState.jobCompleted, uiState.route?.id) {
        val routeIdToOpen = uiState.route?.id
        if (!scanAutoOpened && uiState.jobStarted && !uiState.jobCompleted && routeIdToOpen != null) {
            scanAutoOpened = true
            onStartScan(routeIdToOpen)
        }
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = GccGreen)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Map section (55% height)
            Box(modifier = Modifier.weight(0.55f)) {
                val authoredRings = remember(uiState.route) {
                    uiState.route?.innerBoundary.authoredInnerRings()
                }
                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = uiState.currentLocation?.lat
                        ?: uiState.route?.routeStartCoordinates()?.lat
                        ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT,
                    centerLng = uiState.currentLocation?.lng
                        ?: uiState.route?.routeStartCoordinates()?.lng
                        ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    currentLocation = uiState.currentLocation,
                    routeCoordinates = uiState.route?.let { listOf(it.routePathCoordinates()) } ?: emptyList(),
                    innerBoundaryRings = authoredRings,
                    innerBoundaryFallback = authoredRings.isEmpty(),
                )
            }

            // Bottom panel
            Card(
                modifier = Modifier.weight(0.45f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSubtle.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Show "Start Route" button if job is claimed but not yet started
                    if (!uiState.jobStarted && !uiState.jobCompleted) {
                        val countdown = testAssignmentCountdownText(uiState.job, nowMillis)
                        val startWindowExpired = countdown?.isExpired == true
                        val canStartRoute = uiState.isWithinStartArea && !startWindowExpired
                        if (startWindowExpired) {
                            SectionHeader(title = "Assignment Expired")
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "This test assignment is no longer active. It will disappear from the dashboard after refresh; ask an admin to reassign it for a new start window.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            GccButton(
                                text = "Back to Dashboard",
                                onClick = onBack,
                                variant = GccButtonVariant.PRIMARY,
                            )
                        } else {
                            SectionHeader(title = "Ready to Begin")
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "Go to the assigned route boundary to accept and start.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.distanceToRouteMeters?.let { distance ->
                                    if (uiState.isWithinStartArea) {
                                        "GPS confirmed you are at the route boundary."
                                    } else {
                                        "Route boundary: ${distance.fmt(0)}m away"
                                    }
                                } ?: "Waiting for GPS to confirm your distance to the route boundary.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (uiState.isWithinStartArea) GccGreen else TextMuted,
                            )
                            Spacer(modifier = Modifier.weight(1f))

                            uiState.error?.let { error ->
                                Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            countdown?.let {
                                TestAssignmentCountdownCard(
                                    text = it.text,
                                    isExpired = false,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            GccButton(
                                text = if (uiState.isWithinStartArea) "Start Route" else "Go to Location to Start",
                                onClick = { viewModel.startJob() },
                                variant = GccButtonVariant.PRIMARY,
                                enabled = !uiState.isStarting && canStartRoute,
                                isLoading = uiState.isStarting,
                            )
                            if (uiState.job?.status == JobStatus.CLAIMED && !uiState.isWithinStartArea) {
                                Spacer(modifier = Modifier.height(8.dp))
                                GccButton(
                                    text = if (uiState.isDeclining) "Declining..." else "Decline Assignment",
                                    onClick = { viewModel.declineJob() },
                                    variant = GccButtonVariant.OUTLINED,
                                    enabled = !uiState.isDeclining,
                                    isLoading = uiState.isDeclining,
                                )
                            }
                        }
                    } else if (uiState.jobCompleted) {
                        // Job completed — show success. A paid job lands in
                        // PENDING_REVIEW until an admin approves the payout.
                        val inReview = uiState.job?.status == JobStatus.PENDING_REVIEW
                        // An admin denial leaves the job PENDING_REVIEW with
                        // reviewStatus "denied" and a reason the worker should see.
                        val denied = inReview && uiState.job?.isPayoutDenied() == true
                        SectionHeader(
                            title = when {
                                denied -> "Payout denied"
                                inReview -> "Submitted for review"
                                else -> "Route Complete!"
                            },
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            when {
                                denied -> uiState.job?.denyReason?.takeIf { it.isNotBlank() }
                                    ?.let { "An admin denied this job's payout: $it" }
                                    ?: "An admin denied this job's payout."
                                inReview -> "Both laps submitted. Your payout lands in your wallet once an admin approves the job."
                                else -> "Both laps submitted. Great work!"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (denied) GccError else GccGreen,
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        GccButton(
                            text = "Back to Dashboard",
                            onClick = onBack,
                            variant = GccButtonVariant.PRIMARY,
                        )
                    } else {
                        // Job in progress — the work happens in the two-lap scan
                        SectionHeader(title = "Route Scan In Progress")
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "This assignment is completed with the two-lap scan: " +
                                "Lap 1 documents the whole block, Lap 2 cleans and " +
                                "re-scans everything you flagged.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        uiState.error?.let { error ->
                            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (uiState.isAdminMode) {
                            GccButton(
                                text = "Admin: Skip to Complete",
                                onClick = { viewModel.completeJob() },
                                variant = GccButtonVariant.SECONDARY,
                                isLoading = uiState.isCompleting,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        GccButton(
                            text = "Open Route Scan",
                            onClick = { uiState.route?.id?.let(onStartScan) },
                            variant = GccButtonVariant.PRIMARY,
                            enabled = uiState.route != null,
                        )
                    }
                }
            }
        }

        // Floating route name card on top of map
        uiState.route?.let { route ->
            GccCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 56.dp, end = 16.dp),
                elevation = 4.dp,
            ) {
                Text(
                    route.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    route.code,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        // Floating back button
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopStart),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "←",
                    style = MaterialTheme.typography.titleMedium,
                    color = GccGreenDark,
                )
            }
        }
    }
}

private data class TestAssignmentCountdown(
    val text: String,
    val isExpired: Boolean,
)

private fun testAssignmentCountdownText(job: Job?, nowMillis: Long): TestAssignmentCountdown? {
    if (job?.isTestAssignment() != true || job.status != JobStatus.CLAIMED) return null
    val deadline = job.testAssignmentStartDeadlineMillis() ?: return null
    val remainingMillis = deadline - nowMillis
    if (remainingMillis <= 0) {
        return TestAssignmentCountdown(
            text = "Test start window expired. Ask an admin to reassign the route.",
            isExpired = true,
        )
    }
    val totalSeconds = remainingMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return TestAssignmentCountdown(
        text = "Start this test route within ${minutes}:${seconds.toString().padStart(2, '0')} (${TEST_ASSIGNMENT_START_WINDOW_MINUTES} min window).",
        isExpired = false,
    )
}

@Composable
private fun TestAssignmentCountdownCard(
    text: String,
    isExpired: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = (if (isExpired) GccError else GccClaimed).copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isExpired) GccError else GccClaimed,
            modifier = Modifier.padding(12.dp),
        )
    }
}
