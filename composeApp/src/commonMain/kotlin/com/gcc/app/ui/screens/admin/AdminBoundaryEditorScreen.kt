package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccSky
import com.gcc.app.ui.theme.GccSkyDark
import com.gcc.app.ui.theme.TextSubtle
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.pointInPolygon
import com.gcc.shared.model.GeoJSONMultiPolygon
import com.gcc.shared.model.Route
import com.gcc.shared.model.UpdateBlockBoundariesRequest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow

/** A ring of (lat, lng) vertices, held UNCLOSED (first point not repeated). */
private typealias Ring = List<Pair<Double, Double>>

private val IslandAmber = Color(0xFFF59E0B)
private val IslandAmberBright = Color(0xFFFFB300)

private enum class EditorMode { PAN, DRAW, MOVE }

/**
 * Native admin editor for a route's INNER boundary islands — the
 * private-property exclusion zones cut out of the scannable corridor.
 *
 * MapLibre gives us no tap→latlng or draggable annotations, so this uses the
 * proven crosshair idiom from [AdminSpotCreateScreen]: the map renders with
 * gestures disabled and a full-size Canvas both draws the geometry (projected
 * around the map center, same Mercator meters→pixels math as
 * ScanMinimapOverlay) and handles drags. Dragging steers the map center under
 * a fixed crosshair; "Add point" drops a vertex at the crosshair. In Move
 * mode the drag translates the selected island instead of panning.
 *
 * Saves via PUT /api/admin/blocks/{blockId}/boundaries with an innerBoundary
 * MultiPolygon only (the outer boundary is never sent in v1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminBoundaryEditorScreen(
    routeId: Int,
    blockId: Int,
    onBack: () -> Unit,
) {
    val apiClient = koinInject<GccApiClient>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Route load (full payload: admin list has no boundary geometry) ──
    var route by remember(routeId) { mutableStateOf<Route?>(null) }
    var loadError by remember(routeId) { mutableStateOf<String?>(null) }
    LaunchedEffect(routeId) {
        try {
            route = apiClient.getRoute(routeId)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load route"
        }
    }

    // Outer boundary ring, (lat, lng), unclosed.
    val outerRing: Ring = remember(route) {
        route?.blockBoundary?.coordinates?.firstOrNull()?.let { uncloseRing(it.map { pt -> pt[1] to pt[0] }) }
            ?: emptyList()
    }

    // ── Island state ────────────────────────────────────────────────────
    // Islands are held unclosed; rings are re-closed only on serialize.
    var savedIslands by remember { mutableStateOf<List<Ring>>(emptyList()) }
    var islands by remember { mutableStateOf<List<Ring>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        if (!initialized) {
            val initial = r.innerBoundary?.coordinates.orEmpty().mapNotNull { polygon ->
                polygon.firstOrNull()?.let { ring -> uncloseRing(ring.map { pt -> pt[1] to pt[0] }) }
            }.filter { it.size >= 3 }
            savedIslands = initial
            islands = initial
            initialized = true
        }
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf(EditorMode.PAN) }
    var draft by remember { mutableStateOf<Ring>(emptyList()) }
    var undoStack by remember { mutableStateOf<List<List<Ring>>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }

    fun pushUndo() {
        undoStack = (undoStack + listOf(islands)).takeLast(50)
    }

    fun warnIfOutside(ring: Ring) {
        if (outerRing.size >= 3 && ring.any { !pointInPolygon(it.first, it.second, outerRing) }) {
            scope.launch {
                snackbarHostState.showSnackbar("Warning: island extends outside the block boundary")
            }
        }
    }

    // ── Camera ──────────────────────────────────────────────────────────
    var centerLat by remember { mutableStateOf(0.0) }
    var centerLng by remember { mutableStateOf(0.0) }
    var zoom by remember { mutableStateOf(17.0) }
    var framed by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Inner Boundaries${route?.let { " · ${it.code}" } ?: ""}") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSkyDark,
                    titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            EditorToolbar(
                mode = mode,
                onModeChange = { newMode ->
                    if (newMode != EditorMode.DRAW) draft = emptyList()
                    mode = newMode
                },
                draftCount = draft.size,
                onAddPoint = { draft = draft + (centerLat to centerLng) },
                onCloseDraft = {
                    if (draft.size >= 3) {
                        pushUndo()
                        islands = islands + listOf(draft)
                        selectedIndex = islands.lastIndex
                        warnIfOutside(draft)
                        draft = emptyList()
                        mode = EditorMode.PAN
                    }
                },
                onCancelDraft = {
                    draft = emptyList()
                    mode = EditorMode.PAN
                },
                islandCount = islands.size,
                selectedIndex = selectedIndex,
                onCycleSelection = { delta ->
                    // Cycle none → 1 → … → n → none.
                    val n = islands.size
                    if (n > 0) {
                        selectedIndex = when (val cur = selectedIndex) {
                            null -> if (delta > 0) 0 else n - 1
                            else -> {
                                val next = cur + delta
                                if (next < 0 || next >= n) null else next
                            }
                        }
                        if (selectedIndex == null && mode == EditorMode.MOVE) mode = EditorMode.PAN
                    }
                },
                onResize = { factor ->
                    val idx = selectedIndex ?: return@EditorToolbar
                    val island = islands[idx]
                    val scaled = scaleRing(island, factor)
                    if (scaled != null) {
                        pushUndo()
                        islands = islands.toMutableList().also { it[idx] = scaled }
                        warnIfOutside(scaled)
                    }
                },
                onDelete = {
                    val idx = selectedIndex ?: return@EditorToolbar
                    pushUndo()
                    islands = islands.toMutableList().also { it.removeAt(idx) }
                    selectedIndex = null
                    if (mode == EditorMode.MOVE) mode = EditorMode.PAN
                },
                canUndo = undoStack.isNotEmpty(),
                onUndo = {
                    undoStack.lastOrNull()?.let { prev ->
                        undoStack = undoStack.dropLast(1)
                        islands = prev
                        if ((selectedIndex ?: -1) >= prev.size) selectedIndex = null
                    }
                },
                isDirty = islands != savedIslands,
                onReset = {
                    pushUndo()
                    islands = savedIslands
                    selectedIndex = null
                },
                saving = saving,
                onSave = {
                    saving = true
                    scope.launch {
                        try {
                            val body = UpdateBlockBoundariesRequest(
                                innerBoundary = if (islands.isEmpty()) null else islandsToMultiPolygon(islands),
                            )
                            apiClient.updateBlockBoundaries(blockId, body)
                            savedIslands = islands
                            snackbarHostState.showSnackbar(
                                if (islands.isEmpty()) "Saved — all islands cleared"
                                else "Saved ${islands.size} island${if (islands.size == 1) "" else "s"}"
                            )
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(e.message ?: "Save failed")
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            loadError != null -> CenteredMessage(loadError!!, onBack, Modifier.padding(padding))
            route == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GccGreen)
            }
            outerRing.size < 3 -> CenteredMessage(
                "This route's block has no outer boundary — nothing to edit against.",
                onBack,
                Modifier.padding(padding),
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val density = LocalDensity.current
                val boxWidthPx = with(density) { maxWidth.toPx() }
                val boxHeightPx = with(density) { maxHeight.toPx() }

                // Fit the outer boundary bbox with padding (invert metersToPixels:
                // z = log2(156543.03392 · cos(lat) · px / spanMeters)).
                LaunchedEffect(outerRing, boxWidthPx, boxHeightPx) {
                    if (!framed && boxWidthPx > 0 && boxHeightPx > 0) {
                        val minLat = outerRing.minOf { it.first }
                        val maxLat = outerRing.maxOf { it.first }
                        val minLng = outerRing.minOf { it.second }
                        val maxLng = outerRing.maxOf { it.second }
                        centerLat = (minLat + maxLat) / 2.0
                        centerLng = (minLng + maxLng) / 2.0
                        val spanNorth = (maxLat - minLat) * 111_320.0
                        val spanEast = (maxLng - minLng) * 111_320.0 * cos(centerLat * PI / 180.0)
                        val spanMeters = maxOf(spanNorth, spanEast, 30.0) * 1.3
                        val boxPx = minOf(boxWidthPx, boxHeightPx)
                        zoom = log2(156543.03392 * cos(centerLat * PI / 180.0) * boxPx / spanMeters)
                            .coerceIn(13.0, 19.5)
                        framed = true
                    }
                }

                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    enableGestures = false,
                    showBuildings = false,
                    showGridLines = false,
                )

                // Overlay: draws boundaries AND owns the drag gesture.
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    // One undo snapshot per Move gesture.
                                    if (mode == EditorMode.MOVE && selectedIndex != null) pushUndo()
                                },
                                onDragEnd = {
                                    if (mode == EditorMode.MOVE) {
                                        selectedIndex?.let { idx -> islands.getOrNull(idx)?.let { warnIfOutside(it) } }
                                    }
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                val mpp = 156543.03392 * cos(centerLat * PI / 180.0) / 2.0.pow(zoom)
                                val dLat = -(dragAmount.y * mpp) / 111_320.0
                                val dLng = (dragAmount.x * mpp) / (111_320.0 * cos(centerLat * PI / 180.0))
                                val idx = selectedIndex
                                if (mode == EditorMode.MOVE && idx != null && idx < islands.size) {
                                    // Translate the selected island (island follows the finger).
                                    islands = islands.toMutableList().also { list ->
                                        list[idx] = list[idx].map { (lat, lng) -> (lat + dLat) to (lng + dLng) }
                                    }
                                } else {
                                    // Pan: move the map center under the fixed crosshair.
                                    centerLat = (centerLat + dLat).coerceIn(-85.0, 85.0)
                                    centerLng = (centerLng + dLng).coerceIn(-180.0, 180.0)
                                }
                            }
                        },
                ) {
                    val lngScale = cos(centerLat * PI / 180.0)

                    fun toOffset(lat: Double, lng: Double): Offset {
                        val eastMeters = (lng - centerLng) * 111_320.0 * lngScale
                        val northMeters = (lat - centerLat) * 111_320.0
                        return Offset(
                            size.width / 2f + metersToPixels(eastMeters, centerLat, zoom),
                            size.height / 2f - metersToPixels(northMeters, centerLat, zoom),
                        )
                    }

                    fun ringPath(ring: Ring): Path {
                        val path = Path()
                        ring.forEachIndexed { i, (lat, lng) ->
                            val p = toOffset(lat, lng)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        path.close()
                        return path
                    }

                    // Outer boundary — green stroke.
                    drawPath(ringPath(outerRing), color = GccGreen, style = Stroke(width = 2.5.dp.toPx()))

                    // Inner islands — amber fill + stroke; selected drawn brighter/thicker.
                    islands.forEachIndexed { i, island ->
                        if (island.size < 3) return@forEachIndexed
                        val selected = i == selectedIndex
                        val path = ringPath(island)
                        val fill = if (selected) IslandAmberBright.copy(alpha = 0.35f) else IslandAmber.copy(alpha = 0.25f)
                        val strokeColor = if (selected) IslandAmberBright else IslandAmber.copy(alpha = 0.85f)
                        drawPath(path, color = fill)
                        drawPath(path, color = strokeColor, style = Stroke(width = (if (selected) 3.dp else 1.5.dp).toPx()))
                        if (selected) {
                            island.forEach { (lat, lng) ->
                                val p = toOffset(lat, lng)
                                drawCircle(color = Color.White, radius = 4.5.dp.toPx(), center = p)
                                drawCircle(color = IslandAmberBright, radius = 3.dp.toPx(), center = p)
                            }
                        }
                    }

                    // In-progress draft island — points + connecting lines.
                    if (draft.isNotEmpty()) {
                        val pts = draft.map { (lat, lng) -> toOffset(lat, lng) }
                        for (i in 0 until pts.size - 1) {
                            drawLine(color = GccSky, start = pts[i], end = pts[i + 1], strokeWidth = 2.dp.toPx())
                        }
                        if (pts.size >= 3) {
                            drawLine(
                                color = GccSky.copy(alpha = 0.4f),
                                start = pts.last(),
                                end = pts.first(),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                        pts.forEach { p ->
                            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = p)
                            drawCircle(color = GccSky, radius = 3.5.dp.toPx(), center = p)
                        }
                    }

                    // Fixed center crosshair (the "pen tip" for Add point).
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val crossColor = if (mode == EditorMode.MOVE) IslandAmberBright else GccSky
                    drawCircle(color = crossColor, radius = 20f, center = center, style = Stroke(width = 2f))
                    drawLine(crossColor.copy(alpha = 0.6f), Offset(center.x - 30f, center.y), Offset(center.x + 30f, center.y), 1.5f)
                    drawLine(crossColor.copy(alpha = 0.6f), Offset(center.x, center.y - 30f), Offset(center.x, center.y + 30f), 1.5f)
                    drawCircle(color = crossColor, radius = 3f, center = center)
                }

                // Zoom nudge buttons — precision island work needs closer zoom
                // than the block-fit frame.
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZoomButton("+") { zoom = (zoom + 0.5).coerceAtMost(20.0) }
                    ZoomButton("−") { zoom = (zoom - 0.5).coerceAtLeast(13.0) }
                }

                // Mode hint pill.
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        when {
                            mode == EditorMode.DRAW -> "Drag to aim, tap Add point (${draft.size} placed)"
                            mode == EditorMode.MOVE -> "Drag to move island ${(selectedIndex ?: 0) + 1}"
                            else -> "Drag to pan"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CenteredMessage(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSubtle)
            TextButton(onClick = onBack) { Text("← Back") }
        }
    }
}

@Composable
private fun EditorToolbar(
    mode: EditorMode,
    onModeChange: (EditorMode) -> Unit,
    draftCount: Int,
    onAddPoint: () -> Unit,
    onCloseDraft: () -> Unit,
    onCancelDraft: () -> Unit,
    islandCount: Int,
    selectedIndex: Int?,
    onCycleSelection: (Int) -> Unit,
    onResize: (Double) -> Unit,
    onDelete: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    isDirty: Boolean,
    onReset: () -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Mode row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == EditorMode.PAN,
                    onClick = { onModeChange(EditorMode.PAN) },
                    label = { Text("Pan") },
                )
                FilterChip(
                    selected = mode == EditorMode.DRAW,
                    onClick = { onModeChange(EditorMode.DRAW) },
                    label = { Text("New island") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GccSky.copy(alpha = 0.15f),
                        selectedLabelColor = GccSky,
                    ),
                )
                FilterChip(
                    selected = mode == EditorMode.MOVE,
                    onClick = { onModeChange(EditorMode.MOVE) },
                    enabled = selectedIndex != null,
                    label = { Text("Move") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = IslandAmberBright.copy(alpha = 0.15f),
                        selectedLabelColor = IslandAmberBright,
                    ),
                )
            }

            if (mode == EditorMode.DRAW) {
                // Draft controls
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAddPoint,
                        modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(containerColor = GccSky),
                    ) {
                        Text("Add point ($draftCount)", color = Color.White)
                    }
                    Button(
                        onClick = onCloseDraft,
                        enabled = draftCount >= 3,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                    ) {
                        Text("Close", color = Color.White)
                    }
                    OutlinedButton(onClick = onCancelDraft, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                }
            } else {
                // Selection + shaping controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = { onCycleSelection(-1) },
                        enabled = islandCount > 0,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("◀ Prev") }
                    Text(
                        text = selectedIndex?.let { "Island ${it + 1} of $islandCount" }
                            ?: if (islandCount == 0) "No islands" else "None selected · $islandCount",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        color = if (selectedIndex != null) IslandAmberBright else TextSubtle,
                    )
                    OutlinedButton(
                        onClick = { onCycleSelection(1) },
                        enabled = islandCount > 0,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Next ▶") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Resize", style = MaterialTheme.typography.labelMedium, color = TextSubtle)
                    OutlinedButton(
                        onClick = { onResize(0.9) },
                        enabled = selectedIndex != null,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("−") }
                    OutlinedButton(
                        onClick = { onResize(1.1) },
                        enabled = selectedIndex != null,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("+") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete, enabled = selectedIndex != null) {
                        Text("Delete island", color = if (selectedIndex != null) GccError else TextSubtle)
                    }
                }
            }

            // Persistence row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f)) {
                    Text("Undo")
                }
                OutlinedButton(onClick = onReset, enabled = isDirty, modifier = Modifier.weight(1f)) {
                    Text("Reset to saved")
                }
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}

// ── Geometry helpers ─────────────────────────────────────────────────────

/** Drop the GeoJSON closing vertex (first repeated last) if present. */
private fun uncloseRing(ring: Ring): Ring =
    if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring

/**
 * Serialize islands to a GeoJSON MultiPolygon: [lng, lat] point order, one
 * exterior ring per island, each ring closed (first point repeated last) to
 * match how existing rings arrive from the server.
 */
private fun islandsToMultiPolygon(islands: List<Ring>): GeoJSONMultiPolygon =
    GeoJSONMultiPolygon(
        coordinates = islands.map { ring ->
            val pts = ring.map { (lat, lng) -> listOf(lng, lat) }
            listOf(pts + listOf(pts.first()))
        },
    )

/**
 * Scale a ring by [factor] around its centroid. Returns null when a shrink
 * would collapse the island below ~2 m across (so it can never invert or
 * degenerate — scaling by a positive factor preserves winding).
 */
private fun scaleRing(ring: Ring, factor: Double): Ring? {
    if (ring.size < 3) return null
    val cLat = ring.sumOf { it.first } / ring.size
    val cLng = ring.sumOf { it.second } / ring.size
    val scaled = ring.map { (lat, lng) -> (cLat + (lat - cLat) * factor) to (cLng + (lng - cLng) * factor) }
    if (factor < 1.0) {
        val latSpanM = (scaled.maxOf { it.first } - scaled.minOf { it.first }) * 111_320.0
        val lngSpanM = (scaled.maxOf { it.second } - scaled.minOf { it.second }) * 111_320.0 * cos(cLat * PI / 180.0)
        if (maxOf(latSpanM, lngSpanM) < 2.0) return null
    }
    return scaled
}
