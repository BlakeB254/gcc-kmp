package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
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
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.pickFromLibrary
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteCheckpoint
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRouteDetailScreen(
    routeId: Int,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val route = uiState.routes.find { it.id == routeId }
    val scope = rememberCoroutineScope()
    val cameraManager = remember { CameraManager() }
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()

    var isUploading by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showAddCheckpoint by remember { mutableStateOf(false) }
    var uploadSuccess by remember { mutableStateOf(false) }
    var checkpointList by remember(route) { mutableStateOf(route?.checkpoints ?: emptyList()) }
    var editDisplayName by remember(route) { mutableStateOf(route?.displayName ?: "") }
    var editDescription by remember(route) { mutableStateOf(route?.description ?: "") }
    var nameDescDirty by remember { mutableStateOf(false) }
    var savingNameDesc by remember { mutableStateOf(false) }
    // Track imageUrl to detect when upload completes
    var previousImageUrl by remember { mutableStateOf(route?.imageUrl) }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose { locationProvider.stopLocationUpdates() }
    }

    if (route == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Route not found")
        }
        return
    }

    // Detect when imageUrl changes (upload succeeded)
    LaunchedEffect(route.imageUrl) {
        if (route.imageUrl != previousImageUrl && isUploading) {
            isUploading = false
            uploadSuccess = true
        }
        previousImageUrl = route.imageUrl
    }

    // Detect upload error
    LaunchedEffect(uiState.error) {
        if (uiState.error != null && isUploading) {
            isUploading = false
        }
    }

    // Auto-dismiss success message after 3 seconds
    LaunchedEffect(uploadSuccess) {
        if (uploadSuccess) {
            kotlinx.coroutines.delay(3000)
            uploadSuccess = false
        }
    }

    fun uploadAndSetCover(photoBytes: ByteArray) {
        isUploading = true
        uploadSuccess = false
        viewModel.clearError()
        viewModel.updateRouteImage(routeId, photoBytes)
    }

    // Camera overlay
    if (showCamera) {
        CameraOverlay(
            onDismiss = { showCamera = false },
            onCapture = { photoBytes ->
                showCamera = false
                uploadAndSetCover(photoBytes)
            },
        )
        return
    }

    // Add checkpoint dialog
    if (showAddCheckpoint) {
        AdminAddCheckpointDialog(
            routeId = routeId,
            nextSequenceOrder = checkpointList.size + 1,
            currentLat = currentLocation?.lat ?: 0.0,
            currentLng = currentLocation?.lng ?: 0.0,
            onDismiss = { showAddCheckpoint = false },
            onSave = { request ->
                viewModel.addCheckpoint(routeId, request) { newCheckpoint ->
                    checkpointList = checkpointList + newCheckpoint
                }
                showAddCheckpoint = false
            },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(route.displayName) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGoldDark,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Error banner
            if (uiState.error != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = GccError.copy(alpha = 0.1f),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiState.error ?: "", style = MaterialTheme.typography.bodySmall, color = GccError, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            // Success banner
            if (uploadSuccess) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = GccGreen.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "Cover image updated successfully!",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccGreen,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            // Route image
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isUploading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Uploading...", style = MaterialTheme.typography.bodySmall, color = TextSubtle)
                        }
                    } else if (route.imageUrl != null) {
                        NetworkImage(
                            url = route.imageUrl!!,
                            contentDescription = route.displayName,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("\uD83D\uDDFA\uFE0F", style = MaterialTheme.typography.displaySmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("No display photo", style = MaterialTheme.typography.bodySmall, color = TextSubtle)
                        }
                    }
                }
            }

            // Photo buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showCamera = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                    ) {
                        Text(if (isUploading) "Uploading..." else "\uD83D\uDCF7 Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val photo = pickFromLibrary()
                                if (photo != null) uploadAndSetCover(photo.bytes)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                    ) {
                        Text("\uD83D\uDDBC Library")
                    }
                }
            }

            // Remove image button (only shown when an image exists)
            if (route.imageUrl != null) {
                item {
                    OutlinedButton(
                        onClick = {
                            viewModel.removeRouteImage(routeId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isUploading,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("\uD83D\uDDD1 Remove Cover Image")
                    }
                }
            }

            // Editable name + description
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Display Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = editDisplayName,
                    onValueChange = { editDisplayName = it; nameDescDirty = true },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("e.g. S Pulaski Block 1") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Street Description", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = editDescription,
                    onValueChange = { editDescription = it; nameDescDirty = true },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("e.g. S Pulaski Rd from W Roosevelt Rd to W 16th St") },
                )
                if (nameDescDirty) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            savingNameDesc = true
                            nameDescDirty = false
                            viewModel.updateRouteNameDesc(
                                routeId,
                                editDisplayName.trim(),
                                editDescription.trim().ifEmpty { null },
                            )
                            savingNameDesc = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        enabled = !savingNameDesc && editDisplayName.isNotBlank(),
                    ) {
                        Text(if (savingNameDesc) "Saving..." else "Save Name & Description")
                    }
                }
            }

            // Route info
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(route.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Payout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            route.payoutAmount?.let { cents -> "$${cents / 100}" + if (cents % 100 != 0) ".${(cents % 100).toString().padStart(2, '0')}" else "" } ?: "N/A",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccGreen,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Distance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val distMi = route.distanceMeters?.let { m -> "${((m / 1609.34 * 10).toInt() / 10.0)} mi" } ?: "N/A"
                        Text(distMi, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Checkpoints", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${checkpointList.size}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Checkpoints section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Checkpoints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showAddCheckpoint = true }) {
                        Text("+ Add", color = GccGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            items(checkpointList.sortedBy { it.sequenceOrder }, key = { it.id ?: it.sequenceOrder }) { cp ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(GccGreen),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${cp.sequenceOrder}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cp.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "${cp.type.name.lowercase().replace('_', ' ')}${if (cp.photoRequired) " \u2022 photo" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraOverlay(
    onDismiss: () -> Unit,
    onCapture: (ByteArray) -> Unit,
) {
    val cameraManager = remember { CameraManager() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(modifier = Modifier.fillMaxSize())

        // Close button
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Text("Cancel", color = Color.White)
        }

        // Capture button
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 32.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val photo = cameraManager.capturePhoto()
                        if (photo != null) onCapture(photo.bytes)
                    }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
            ) {
                Text("\uD83D\uDCF8", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
