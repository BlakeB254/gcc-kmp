package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.GccMap
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
import kotlin.math.roundToInt

/** A ring of (lat, lng) vertices, held UNCLOSED (first point not repeated). */
private typealias Ring = List<Pair<Double, Double>>

private val IslandAmber = Color(0xFFF59E0B)
private val IslandAmberBright = Color(0xFFFFB300)

/**
 * Max interactive zoom. The MIN is dynamic: the fit-to-boundary zoom — the
 * editor is for ONE block, so you can zoom in but never out past the framing.
 */
private const val ZOOM_MAX = 19.0

/** What a single-finger gesture is manipulating, decided at pointer-down. */
private enum class DragTarget { PAN, VERTEX, ISLAND }

/**
 * Native admin editor for a route's INNER boundary islands — the
 * private-property exclusion zones cut out of the scannable corridor.
 *
 * Direct-touch interaction model (mirror of the web BoundaryEditor, adapted
 * for fingers): tap an island to select it, drag a vertex handle to reshape,
 * tap an edge midpoint to insert a vertex, drag the island body to move it,
 * drag empty map to pan, pinch to zoom. "+ Island" enters draw mode where
 * each map tap places a vertex and tapping the first point closes the ring.
 *
 * GccMap stays gesture-disabled and state-driven; a full-size Canvas overlay
 * both draws the geometry and owns ALL touch via one custom awaitEachGesture
 * pointer loop. Every pixel↔geo conversion goes through [BoundaryProjection]
 * so hit testing, dragging and rendering always agree.
 *
 * The camera is LOCKED to this route's block: it frames the outer boundary
 * (re-framing once the async route payload lands), min zoom is the fit zoom,
 * pan is clamped so the boundary bbox can't leave the viewport, and the map
 * outside the outer boundary is dimmed with an even-odd shade.
 *
 * Saves via PUT /api/admin/blocks/{blockId}/boundaries with an innerBoundary
 * MultiPolygon only (the outer boundary is never sent from the app).
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

    var selectedIsland by remember { mutableStateOf<Int?>(null) }
    var selectedVertex by remember { mutableStateOf<Int?>(null) }
    var drawMode by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Ring>(emptyList()) }
    var undoStack by remember { mutableStateOf<List<List<Ring>>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var hintDismissed by remember { mutableStateOf(false) }

    /** Push a PRE-edit snapshot — one entry per gesture, never per frame. */
    fun pushUndoSnapshot(prev: List<Ring>) {
        undoStack = (undoStack + listOf(prev)).takeLast(50)
    }

    fun warnIfOutside(ring: Ring) {
        if (outerRing.size >= 3 && ring.any { !pointInPolygon(it.first, it.second, outerRing) }) {
            scope.launch {
                snackbarHostState.showSnackbar("Warning: island extends outside the block boundary")
            }
        }
    }

    fun closeDraft() {
        if (draft.size < 3) return
        pushUndoSnapshot(islands)
        islands = islands + listOf(draft)
        selectedIsland = islands.lastIndex
        selectedVertex = null
        warnIfOutside(draft)
        draft = emptyList()
        drawMode = false
    }

    // ── Camera (locked to the block: min zoom = fit zoom, pan clamped) ──
    var centerLat by remember { mutableStateOf(0.0) }
    var centerLng by remember { mutableStateOf(0.0) }
    var zoom by remember { mutableStateOf(17.0) }
    var fitZoom by remember { mutableStateOf(13.0) }

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
                drawMode = drawMode,
                draftCount = draft.size,
                onStartDraw = {
                    drawMode = true
                    draft = emptyList()
                    selectedIsland = null
                    selectedVertex = null
                },
                onCloseDraft = ::closeDraft,
                onCancelDraft = {
                    draft = emptyList()
                    drawMode = false
                },
                islandCount = islands.size,
                selectedIndex = selectedIsland,
                onDeleteIsland = {
                    val idx = selectedIsland ?: return@EditorToolbar
                    pushUndoSnapshot(islands)
                    islands = islands.toMutableList().also { it.removeAt(idx) }
                    selectedIsland = null
                    selectedVertex = null
                },
                canUndo = undoStack.isNotEmpty(),
                onUndo = {
                    undoStack.lastOrNull()?.let { prev ->
                        undoStack = undoStack.dropLast(1)
                        islands = prev
                        if ((selectedIsland ?: -1) >= prev.size) selectedIsland = null
                        selectedVertex = null
                    }
                },
                isDirty = islands != savedIslands,
                onReset = {
                    pushUndoSnapshot(islands)
                    islands = savedIslands
                    selectedIsland = null
                    selectedVertex = null
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
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                // Slim context header — which block this editor is locked to.
                val routeInfo = route
                if (routeInfo != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                routeInfo.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                routeInfo.code,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSubtle,
                            )
                        }
                    }
                }

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val boxWidthPx = with(density) { maxWidth.toPx() }
                val boxHeightPx = with(density) { maxHeight.toPx() }

                // Outer-boundary bbox: [minLat, maxLat, minLng, maxLng].
                val bbox = remember(outerRing) {
                    listOf(
                        outerRing.minOf { it.first }, outerRing.maxOf { it.first },
                        outerRing.minOf { it.second }, outerRing.maxOf { it.second },
                    )
                }

                // Keep the camera on the block: the center may not leave the
                // boundary bbox expanded by ~50% of the viewport span.
                fun clampedCenter(lat: Double, lng: Double, z: Double, wPx: Float, hPx: Float): Pair<Double, Double> {
                    val lngScale = cos(lat * PI / 180.0)
                    val mpp = 156543.03392 * lngScale / 2.0.pow(z)
                    val halfLat = hPx * mpp / 111_320.0 / 2.0
                    val halfLng = wPx * mpp / (111_320.0 * lngScale) / 2.0
                    return lat.coerceIn(bbox[0] - halfLat, bbox[1] + halfLat) to
                        lng.coerceIn(bbox[2] - halfLng, bbox[3] + halfLng)
                }

                // Fit the outer boundary bbox with ~15% padding per side
                // (invert metersToPixels: z = log2(156543.03392·cos(lat)·px/spanMeters)).
                // Keyed on outerRing: the route arrives ASYNC, so the frame
                // must (re)run after blockBoundary lands, not just at mount.
                LaunchedEffect(outerRing, boxWidthPx, boxHeightPx) {
                    if (boxWidthPx > 0 && boxHeightPx > 0) {
                        centerLat = (bbox[0] + bbox[1]) / 2.0
                        centerLng = (bbox[2] + bbox[3]) / 2.0
                        val spanNorth = (bbox[1] - bbox[0]) * 111_320.0
                        val spanEast = (bbox[3] - bbox[2]) * 111_320.0 * cos(centerLat * PI / 180.0)
                        val spanMeters = maxOf(spanNorth, spanEast, 30.0) * 1.3
                        val boxPx = minOf(boxWidthPx, boxHeightPx)
                        fitZoom = log2(156543.03392 * cos(centerLat * PI / 180.0) * boxPx / spanMeters)
                            .coerceIn(13.0, ZOOM_MAX)
                        zoom = fitZoom
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

                // Overlay: draws boundaries AND owns ALL touch. One custom
                // pointer loop — 2+ pointers pinch-zoom/pan, 1 pointer runs
                // the hit-test logic (vertex > midpoint > island body > pan).
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            fun projection() = BoundaryProjection(
                                centerLat, centerLng, zoom,
                                size.width.toFloat(), size.height.toFloat(),
                            )

                            /** Pan so the map follows the finger (inverse delta on center). */
                            fun panBy(delta: Offset) {
                                val p = projection()
                                val (la, ln) = clampedCenter(
                                    centerLat + delta.y * p.metersPerPixel / 111_320.0,
                                    centerLng - delta.x * p.metersPerPixel / (111_320.0 * p.lngScale),
                                    zoom, size.width.toFloat(), size.height.toFloat(),
                                )
                                centerLat = la
                                centerLng = ln
                            }

                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downPos = down.position
                                val slop = viewConfiguration.touchSlop
                                val vertexHitPx = 24.dp.toPx()
                                val midpointHitPx = 22.dp.toPx()
                                val edgeSlopPx = 16.dp.toPx()

                                var target = DragTarget.PAN
                                var dragVertex = -1
                                var tappedIsland: Int? = null
                                var preGesture: List<Ring>? = null
                                var edited = false
                                var pinched = false
                                var pastSlop = false

                                // ── Classify the pointer-down ──────────────
                                val p0 = projection()
                                val selIdx = selectedIsland
                                val sel = if (drawMode) null else selIdx?.let { islands.getOrNull(it) }
                                if (sel != null && selIdx != null) {
                                    val pts = sel.map { (la, ln) -> p0.project(la, ln) }
                                    // 1) Vertex handle of the selected island.
                                    val vi = pts.indices
                                        .minByOrNull { (pts[it] - downPos).getDistance() }
                                        ?.takeIf { (pts[it] - downPos).getDistance() <= vertexHitPx }
                                    if (vi != null) {
                                        target = DragTarget.VERTEX
                                        dragVertex = vi
                                        preGesture = islands
                                    } else {
                                        // 2) Edge-midpoint dot → insert a vertex there,
                                        // then keep dragging it (one undo entry total).
                                        val mids = List(pts.size) { i ->
                                            (pts[i] + pts[(i + 1) % pts.size]) / 2f
                                        }
                                        val mi = mids.indices
                                            .minByOrNull { (mids[it] - downPos).getDistance() }
                                            ?.takeIf { (mids[it] - downPos).getDistance() <= midpointHitPx }
                                        if (mi != null) {
                                            preGesture = islands
                                            val (mLat, mLng) = p0.unproject(mids[mi])
                                            islands = islands.toMutableList().also { list ->
                                                list[selIdx] = list[selIdx].toMutableList()
                                                    .also { it.add(mi + 1, mLat to mLng) }
                                            }
                                            target = DragTarget.VERTEX
                                            dragVertex = mi + 1
                                            selectedVertex = mi + 1
                                            edited = true
                                        }
                                    }
                                }
                                if (target == DragTarget.PAN && !drawMode) {
                                    // 3) Island body (generous hit slop around edges).
                                    fun hits(ring: Ring): Boolean {
                                        val pts = ring.map { (la, ln) -> p0.project(la, ln) }
                                        return screenPointInPolygon(downPos, pts) ||
                                            distanceToPolygonEdge(downPos, pts) <= edgeSlopPx
                                    }
                                    val hitIdx = when {
                                        selIdx != null && islands.getOrNull(selIdx)?.let(::hits) == true -> selIdx
                                        else -> islands.indices.lastOrNull { hits(islands[it]) }
                                    }
                                    if (hitIdx != null) {
                                        tappedIsland = hitIdx
                                        if (hitIdx == selIdx) {
                                            // Drag inside the selected island moves it.
                                            target = DragTarget.ISLAND
                                            preGesture = islands
                                        }
                                    }
                                    // 4) Otherwise: empty map → pan / tap-deselect.
                                }

                                // ── Track the gesture ──────────────────────
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2) {
                                        // Pinch: zoom around the centroid, centroid
                                        // pan comes along for free. Any in-progress
                                        // edit is finalized first.
                                        if (!pinched) {
                                            pinched = true
                                            if (edited) {
                                                preGesture?.let(::pushUndoSnapshot)
                                                edited = false
                                                preGesture = null
                                            }
                                        }
                                        val a = pressed[0]
                                        val b = pressed[1]
                                        val oldCentroid = (a.previousPosition + b.previousPosition) / 2f
                                        val newCentroid = (a.position + b.position) / 2f
                                        val oldDist = (a.previousPosition - b.previousPosition).getDistance()
                                        val newDist = (a.position - b.position).getDistance()
                                        val cur = projection()
                                        val (gLat, gLng) = cur.unproject(oldCentroid)
                                        val newZoom = if (oldDist > 8f && newDist > 8f) {
                                            (zoom + log2(newDist.toDouble() / oldDist))
                                                .coerceIn(minOf(fitZoom, ZOOM_MAX), ZOOM_MAX)
                                        } else zoom
                                        // Solve the center that keeps the geo point
                                        // under the pinch centroid geo-fixed.
                                        val solved = BoundaryProjection(
                                            centerLat, centerLng, newZoom,
                                            size.width.toFloat(), size.height.toFloat(),
                                        ).centerFor(gLat, gLng, newCentroid)
                                        val clamped = clampedCenter(
                                            solved.first, solved.second, newZoom,
                                            size.width.toFloat(), size.height.toFloat(),
                                        )
                                        zoom = newZoom
                                        centerLat = clamped.first
                                        centerLng = clamped.second
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    val change = pressed[0]
                                    val delta = change.position - change.previousPosition
                                    if (!pastSlop && (change.position - downPos).getDistance() > slop) {
                                        pastSlop = true
                                    }
                                    when {
                                        pinched -> if (delta != Offset.Zero) panBy(delta) // finger left after pinch
                                        target == DragTarget.VERTEX && (pastSlop || edited) -> {
                                            // Vertex rides the finger (absolute position).
                                            val si = selectedIsland
                                            if (si != null && dragVertex < (islands.getOrNull(si)?.size ?: 0)) {
                                                val (la, ln) = projection().unproject(change.position)
                                                islands = islands.toMutableList().also { list ->
                                                    list[si] = list[si].toMutableList()
                                                        .also { it[dragVertex] = la to ln }
                                                }
                                                edited = true
                                            }
                                        }
                                        target == DragTarget.ISLAND && pastSlop -> {
                                            // Island follows the finger.
                                            val si = selectedIsland
                                            if (si != null && si < islands.size && delta != Offset.Zero) {
                                                val cur = projection()
                                                val dLat = -delta.y * cur.metersPerPixel / 111_320.0
                                                val dLng = delta.x * cur.metersPerPixel / (111_320.0 * cur.lngScale)
                                                islands = islands.toMutableList().also { list ->
                                                    list[si] = list[si].map { (la, ln) -> (la + dLat) to (ln + dLng) }
                                                }
                                                edited = true
                                            }
                                        }
                                        target == DragTarget.PAN && pastSlop -> {
                                            if (delta != Offset.Zero) panBy(delta)
                                        }
                                    }
                                    change.consume()
                                }

                                // ── Gesture end ────────────────────────────
                                if (edited) {
                                    // One undo entry for the whole gesture.
                                    preGesture?.let(::pushUndoSnapshot)
                                    selectedIsland?.let { si -> islands.getOrNull(si)?.let(::warnIfOutside) }
                                } else if (!pinched && !pastSlop) {
                                    // A TAP.
                                    if (drawMode) {
                                        val p = projection()
                                        val closeIt = draft.size >= 3 &&
                                            (p.project(draft.first().first, draft.first().second) - downPos)
                                                .getDistance() <= vertexHitPx
                                        if (closeIt) closeDraft() else draft = draft + p.unproject(downPos)
                                    } else when {
                                        target == DragTarget.VERTEX ->
                                            selectedVertex = if (selectedVertex == dragVertex) null else dragVertex
                                        tappedIsland != null -> {
                                            if (selectedIsland != tappedIsland) {
                                                selectedIsland = tappedIsland
                                            }
                                            selectedVertex = null
                                        }
                                        else -> {
                                            selectedIsland = null
                                            selectedVertex = null
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    val proj = BoundaryProjection(centerLat, centerLng, zoom, size.width, size.height)

                    fun ringPath(ring: Ring): Path {
                        val path = Path()
                        ring.forEachIndexed { i, (lat, lng) ->
                            val p = proj.project(lat, lng)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        path.close()
                        return path
                    }

                    // Dim everything OUTSIDE the outer boundary: one even-odd
                    // path (full-canvas rect + boundary ring as the hole) —
                    // blend-mode-free, robust on iOS Skia.
                    val shade = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        outerRing.forEachIndexed { i, (lat, lng) ->
                            val p = proj.project(lat, lng)
                            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                        close()
                    }
                    drawPath(shade, color = Color.Black.copy(alpha = 0.45f))

                    // Outer boundary — green stroke (not editable here).
                    drawPath(ringPath(outerRing), color = GccGreen, style = Stroke(width = 2.5.dp.toPx()))

                    // Inner islands — amber fill + stroke; selected drawn brighter/thicker.
                    islands.forEachIndexed { i, island ->
                        if (island.size < 3) return@forEachIndexed
                        val selected = i == selectedIsland
                        val path = ringPath(island)
                        val fill = if (selected) IslandAmberBright.copy(alpha = 0.35f) else IslandAmber.copy(alpha = 0.25f)
                        val strokeColor = if (selected) IslandAmberBright else IslandAmber.copy(alpha = 0.85f)
                        drawPath(path, color = fill)
                        drawPath(path, color = strokeColor, style = Stroke(width = (if (selected) 3.dp else 1.5.dp).toPx()))
                        if (selected) {
                            val pts = island.map { (lat, lng) -> proj.project(lat, lng) }
                            // Edge midpoints — small hollow dots (tap to insert a vertex).
                            for (j in pts.indices) {
                                val mid = (pts[j] + pts[(j + 1) % pts.size]) / 2f
                                drawCircle(Color.Black.copy(alpha = 0.35f), radius = 4.5.dp.toPx(), center = mid, style = Stroke(width = 3.5.dp.toPx()))
                                drawCircle(Color.White, radius = 4.dp.toPx(), center = mid, style = Stroke(width = 2.dp.toPx()))
                            }
                            // Vertex handles — filled; the tapped one highlighted.
                            pts.forEachIndexed { j, p ->
                                val isSel = j == selectedVertex
                                if (isSel) drawCircle(GccSky, radius = 9.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
                                drawCircle(Color.White, radius = (if (isSel) 7.dp else 5.5.dp).toPx(), center = p)
                                drawCircle(IslandAmberBright, radius = (if (isSel) 5.dp else 4.dp).toPx(), center = p)
                            }
                        }
                    }

                    // In-progress draft island — points + connecting lines.
                    if (draft.isNotEmpty()) {
                        val pts = draft.map { (lat, lng) -> proj.project(lat, lng) }
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
                            // First point becomes the close target — ring it.
                            drawCircle(GccGreen, radius = 10.dp.toPx(), center = pts.first(), style = Stroke(width = 2.5.dp.toPx()))
                        }
                        pts.forEach { p ->
                            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = p)
                            drawCircle(color = GccSky, radius = 3.5.dp.toPx(), center = p)
                        }
                    }
                }

                // Floating "remove point" chip near the tap-selected vertex.
                val chipIsland = selectedIsland
                val chipVertex = selectedVertex
                if (!drawMode && chipIsland != null && chipVertex != null) {
                    val ring = islands.getOrNull(chipIsland)
                    val pt = ring?.getOrNull(chipVertex)
                    if (pt != null) {
                        val pos = BoundaryProjection(centerLat, centerLng, zoom, boxWidthPx, boxHeightPx)
                            .project(pt.first, pt.second)
                        val chipW = with(density) { 150.dp.toPx() }
                        val chipH = with(density) { 40.dp.toPx() }
                        val gap = with(density) { 18.dp.toPx() }
                        val x = (pos.x - chipW / 2f).coerceIn(8f, (boxWidthPx - chipW - 8f).coerceAtLeast(8f))
                        val y = (pos.y - chipH - gap).coerceAtLeast(8f)
                        val canRemove = ring.size > 3
                        Surface(
                            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.75f),
                            shadowElevation = 4.dp,
                        ) {
                            Text(
                                text = if (canRemove) "✕ Remove point" else "Min 3 points",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (canRemove) Color.White else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .then(
                                        if (canRemove) Modifier.clickable {
                                            pushUndoSnapshot(islands)
                                            val newRing = ring.toMutableList().also { it.removeAt(chipVertex) }
                                            islands = islands.toMutableList().also { it[chipIsland] = newRing }
                                            selectedVertex = null
                                            warnIfOutside(newRing)
                                        } else Modifier
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                // Zoom nudge buttons — pinch fallback for precision work.
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fun nudgeZoom(delta: Double) {
                        zoom = (zoom + delta).coerceIn(minOf(fitZoom, ZOOM_MAX), ZOOM_MAX)
                        val c = clampedCenter(centerLat, centerLng, zoom, boxWidthPx, boxHeightPx)
                        centerLat = c.first
                        centerLng = c.second
                    }
                    ZoomButton("+") { nudgeZoom(0.5) }
                    ZoomButton("−") { nudgeZoom(-0.5) }
                }

                // Draw-mode banner.
                if (drawMode) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                    ) {
                        Text(
                            if (draft.isEmpty()) "Tap the map to place points · tap the first point to close"
                            else "Tap to place points (${draft.size}) · tap the first point to close",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }

                // First-open hint when there are no islands yet.
                if (initialized && !drawMode && islands.isEmpty() && !hintDismissed) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                "Private-property islands are excluded from required scanning. Tap + Island to draw one.",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSubtle,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { hintDismissed = true }) { Text("✕") }
                        }
                    }
                }
                } // BoxWithConstraints
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
    drawMode: Boolean,
    draftCount: Int,
    onStartDraw: () -> Unit,
    onCloseDraft: () -> Unit,
    onCancelDraft: () -> Unit,
    islandCount: Int,
    selectedIndex: Int?,
    onDeleteIsland: () -> Unit,
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
            if (drawMode) {
                // Draw-mode controls: Done (≥3 points) / Cancel.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "$draftCount point${if (draftCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GccSky,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onCloseDraft,
                        enabled = draftCount >= 3,
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                    ) { Text("Done", color = Color.White) }
                    OutlinedButton(onClick = onCancelDraft) { Text("Cancel") }
                }
            } else {
                // Contextual row for the selected island.
                if (selectedIndex != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Island ${selectedIndex + 1} of $islandCount",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = IslandAmberBright,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDeleteIsland) {
                            Text("Delete island", color = GccError)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStartDraw,
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = GccSky),
                    ) { Text("+ Island", color = Color.White) }
                    OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f)) {
                        Text("Undo")
                    }
                    OutlinedButton(onClick = onReset, enabled = isDirty, modifier = Modifier.weight(1f)) {
                        Text("Reset")
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
