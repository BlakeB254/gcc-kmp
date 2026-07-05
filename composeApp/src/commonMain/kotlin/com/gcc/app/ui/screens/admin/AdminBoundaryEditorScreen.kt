package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.gcc.shared.model.GeoJSONPolygon
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
private val ExclusionGrey = Color(0xFF757575)
private val ExclusionGreyDark = Color(0xFF424242)

/**
 * Max interactive zoom. The MIN is dynamic: the fit-to-boundary zoom — the
 * editor is for ONE block, so you can zoom in but never out past the framing.
 */
private const val ZOOM_MAX = 19.0

/** What a single-finger gesture is manipulating, decided at pointer-down. */
private enum class DragTarget { NONE, VERTEX, RING }

/** Which geometry the editor is working on. */
private enum class EditStep { OUTER, ISLANDS }

/** One undo entry: the full geometry state before a gesture. */
private data class GeoSnapshot(val outer: Ring, val islands: List<Ring>)

/**
 * Boundary Studio — the native admin editor for a block's boundaries.
 *
 * Two explicit steps via a segmented header:
 *  · OUTER — the block's perimeter. Same direct-touch editing as islands
 *    (drag vertices, tap midpoints to insert, drag the body to move the whole
 *    ring). If the block has no boundary yet, this step opens in draw mode.
 *    Saving an edited outer warns that overlapping neighbors get trimmed
 *    (the server's thinning behavior).
 *  · ISLANDS — the private-property exclusion islands. "+ Island" draws one
 *    tap-by-tap; "✨ Suggest" runs the OSM building-frontage generator
 *    (server-side, results load in for adjusting); "Clear" removes all.
 *
 * One gesture contract, no ambiguity: ONE finger only ever edits (tap =
 * select/deselect, drag = move vertex/ring, draw-mode taps place corners; a
 * one-finger drag on empty map does NOTHING). TWO fingers always pan/zoom,
 * clamped so the block can never leave the viewport. GccMap itself stays
 * gesture-disabled; a full-size Canvas overlay draws all geometry and owns
 * all touch through one awaitEachGesture loop. Every pixel↔geo conversion
 * goes through [BoundaryProjection] so hit testing, dragging and rendering
 * always agree.
 *
 * Saves via PUT /api/admin/blocks/{blockId}/boundaries — innerBoundary
 * always; locationBoundary (outer) only when the outer was actually edited.
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

    // ── Geometry state (outer + islands, all unclosed) ─────────────────
    var savedOuter by remember { mutableStateOf<Ring>(emptyList()) }
    var outer by remember { mutableStateOf<Ring>(emptyList()) }
    var savedIslands by remember { mutableStateOf<List<Ring>>(emptyList()) }
    var islands by remember { mutableStateOf<List<Ring>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(EditStep.OUTER) }
    var drawMode by remember { mutableStateOf(false) }

    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        if (!initialized) {
            // The outer edge MUST be the loop every user-facing map renders:
            // the route's drawn geometry (the original January draws). The
            // block's location_boundary is a separately-seeded polygon only
            // admin/AR code reads — loading it here made the editor show a
            // rectangle nobody recognizes from the main app experience.
            val geometryLoop = r.geometry?.coordinates
                ?.let { uncloseRing(it.map { pt -> pt[1] to pt[0] }) }
                ?.takeIf { it.size >= 3 }
            val boundaryLoop = r.blockBoundary?.coordinates?.firstOrNull()
                ?.let { uncloseRing(it.map { pt -> pt[1] to pt[0] }) }
                ?.takeIf { it.size >= 3 }
            val serverOuter = geometryLoop ?: boundaryLoop ?: emptyList()
            savedOuter = serverOuter
            outer = serverOuter
            val initial = r.innerBoundary?.coordinates.orEmpty().mapNotNull { polygon ->
                polygon.firstOrNull()?.let { ring -> uncloseRing(ring.map { pt -> pt[1] to pt[0] }) }
            }.filter { it.size >= 3 }
            savedIslands = initial
            islands = initial
            // No boundary yet? The flow starts by drawing one.
            if (serverOuter.isEmpty()) {
                step = EditStep.OUTER
                drawMode = true
            }
            initialized = true
        }
    }

    var selectedIsland by remember { mutableStateOf<Int?>(null) }
    var selectedVertex by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf<Ring>(emptyList()) }
    var undoStack by remember { mutableStateOf<List<GeoSnapshot>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var suggesting by remember { mutableStateOf(false) }
    var hintDismissed by remember { mutableStateOf(false) }
    var confirmOuterSave by remember { mutableStateOf(false) }

    val outerDirty = outer != savedOuter
    val islandsDirty = islands != savedIslands

    /** Push a PRE-edit snapshot — one entry per gesture, never per frame. */
    fun pushUndoSnapshot(prevOuter: Ring = outer, prevIslands: List<Ring> = islands) {
        undoStack = (undoStack + GeoSnapshot(prevOuter, prevIslands)).takeLast(50)
    }

    fun warnIfOutside(ring: Ring) {
        if (outer.size >= 3 && ring.any { !pointInPolygon(it.first, it.second, outer) }) {
            scope.launch {
                snackbarHostState.showSnackbar("Warning: exclusion zone extends outside the perimeter")
            }
        }
    }

    fun closeDraft() {
        if (draft.size < 3) return
        pushUndoSnapshot()
        if (step == EditStep.OUTER) {
            outer = draft
        } else {
            islands = islands + listOf(draft)
            selectedIsland = islands.lastIndex
            warnIfOutside(draft)
        }
        selectedVertex = null
        draft = emptyList()
        drawMode = false
    }

    fun performSave() {
        saving = true
        scope.launch {
            try {
                val body = UpdateBlockBoundariesRequest(
                    innerBoundary = if (islands.isEmpty()) null else islandsToMultiPolygon(islands),
                    locationBoundary = if (outerDirty && outer.size >= 3) ringToPolygon(outer) else null,
                )
                apiClient.updateBlockBoundaries(blockId, body)
                val outerWasDirty = outerDirty
                savedIslands = islands
                savedOuter = outer
                snackbarHostState.showSnackbar(
                    buildString {
                        append("Saved")
                        if (outerWasDirty) append(" · outer boundary updated")
                        append(" · ${islands.size} island${if (islands.size == 1) "" else "s"}")
                    }
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Save failed")
            } finally {
                saving = false
            }
        }
    }

    /**
     * Corridor preset: ONE island = the outer ring inset inward — exactly
     * the shape the route map renders as the "inner boundary" band (same
     * inset math as the worker-facing double-lined route). The area between
     * outer and this ring is the scannable corridor; adjust vertices to fit.
     */
    fun makeCorridor() {
        if (outer.size < 3) return
        val insetRing = com.gcc.app.ui.map.insetRingByEdgeOffset(
            outer.map { (lat, lng) -> com.gcc.shared.model.Coordinates(lat = lat, lng = lng) }
                .let { it + it.first() }, // closed ring expected
            insetMeters = 8.0,
        )?.map { it.lat to it.lng }
        if (insetRing == null || insetRing.size < 3) {
            scope.launch { snackbarHostState.showSnackbar("Couldn't inset this shape — adjust the outer edge first") }
            return
        }
        pushUndoSnapshot()
        islands = listOf(uncloseRing(insetRing))
        selectedIsland = 0
        selectedVertex = null
    }

    fun suggestIslands() {
        // The generator runs server-side from the SAVED boundary — a drawn
        // but unsaved outer would silently suggest against stale geometry.
        if (savedOuter.size < 3 || outerDirty) {
            scope.launch {
                snackbarHostState.showSnackbar("Save the outer edge first — suggestions are generated from the saved boundary")
            }
            return
        }
        suggesting = true
        scope.launch {
            try {
                val result = apiClient.regenerateInnerBoundary(routeId)
                val generated = result.innerBoundary?.coordinates.orEmpty().mapNotNull { polygon ->
                    polygon.firstOrNull()?.let { ring -> uncloseRing(ring.map { pt -> pt[1] to pt[0] }) }
                }.filter { it.size >= 3 }
                pushUndoSnapshot()
                islands = generated
                savedIslands = generated // the generator persists server-side
                selectedIsland = null
                selectedVertex = null
                snackbarHostState.showSnackbar(
                    "Suggested ${generated.size} island${if (generated.size == 1) "" else "s"} from buildings — adjust freely"
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Island suggestion failed")
            } finally {
                suggesting = false
            }
        }
    }

    // ── Camera (locked to the block: min zoom = fit zoom, pan clamped) ──
    var centerLat by remember { mutableStateOf(0.0) }
    var centerLng by remember { mutableStateOf(0.0) }
    var zoom by remember { mutableStateOf(17.0) }
    var fitZoom by remember { mutableStateOf(13.0) }

    if (confirmOuterSave) {
        AlertDialog(
            onDismissRequest = { confirmOuterSave = false },
            title = { Text("Save outer boundary?") },
            text = {
                Text(
                    "You reshaped this block's perimeter. Neighboring blocks that " +
                        "overlap the new edge will be trimmed to fit against it."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmOuterSave = false
                        performSave()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                ) { Text("Save", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOuterSave = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Boundaries${route?.let { " · ${it.code}" } ?: ""}") },
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
                step = step,
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
                    // Backing out of a forced first-draw leaves an empty outer;
                    // that's fine — Save stays meaningful for islands only.
                    drawMode = false
                },
                hasOuter = outer.size >= 3,
                islandCount = islands.size,
                selectedIndex = if (step == EditStep.ISLANDS) selectedIsland else null,
                onDeleteIsland = {
                    val idx = selectedIsland ?: return@EditorToolbar
                    pushUndoSnapshot()
                    islands = islands.toMutableList().also { it.removeAt(idx) }
                    selectedIsland = null
                    selectedVertex = null
                },
                onClearIslands = {
                    if (islands.isEmpty()) return@EditorToolbar
                    pushUndoSnapshot()
                    islands = emptyList()
                    selectedIsland = null
                    selectedVertex = null
                },
                onCorridor = ::makeCorridor,
                suggesting = suggesting,
                onSuggest = ::suggestIslands,
                canUndo = undoStack.isNotEmpty(),
                onUndo = {
                    undoStack.lastOrNull()?.let { prev ->
                        undoStack = undoStack.dropLast(1)
                        outer = prev.outer
                        islands = prev.islands
                        if ((selectedIsland ?: -1) >= prev.islands.size) selectedIsland = null
                        selectedVertex = null
                    }
                },
                isDirty = outerDirty || islandsDirty,
                onReset = {
                    pushUndoSnapshot()
                    outer = savedOuter
                    islands = savedIslands
                    selectedIsland = null
                    selectedVertex = null
                },
                saving = saving,
                onSave = {
                    if (outerDirty && outer.size >= 3) confirmOuterSave = true else performSave()
                },
            )
        },
    ) { padding ->
        when {
            loadError != null -> CenteredMessage(loadError!!, onBack, Modifier.padding(padding))
            route == null || !initialized -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccGreen)
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                // Step header: which geometry the next touch edits.
                val routeInfo = route
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        if (routeInfo != null) {
                            Row(
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
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StepChip(
                                label = "1 · Perimeter",
                                active = step == EditStep.OUTER,
                                enabled = !drawMode,
                            ) {
                                step = EditStep.OUTER
                                selectedIsland = null
                                selectedVertex = null
                            }
                            StepChip(
                                label = "2 · Exclusions (${islands.size})",
                                active = step == EditStep.ISLANDS,
                                enabled = !drawMode && outer.size >= 3,
                            ) {
                                step = EditStep.ISLANDS
                                selectedVertex = null
                            }
                        }
                    }
                }

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val boxWidthPx = with(density) { maxWidth.toPx() }
                val boxHeightPx = with(density) { maxHeight.toPx() }
                // MapLibre's zoom scale is defined per POINT; the overlay
                // canvas works in PHYSICAL pixels (3x on modern iPhones).
                // Every projection built against physical pixels must bias
                // the zoom by log2(density) or geometry draws ~3x too small
                // against the basemap (field screenshots: the block rendered
                // as a small square mid-screen).
                // ×density: MapLibre zoom scale is per POINT, the canvas
                // works in PHYSICAL pixels. ×2: MapLibre zoom is defined
                // against 512px tiles — one full level below the classic
                // 256-tile 156543/2^z formula. Verified empirically against
                // a MapLibre-rendered underlay of the same polygon (the
                // canvas ring drew concentric at ~60% scale before the ×2).
                val zoomBias = log2(density.density.toDouble() * 2.0)

                // Framing geometry: the SAVED outer (stable while dragging the
                // live one), falling back to the route line for boundary-less
                // blocks so first-draw still frames the right street.
                val frameRing: Ring = remember(savedOuter, route) {
                    savedOuter.takeIf { it.size >= 3 }
                        ?: route?.geometry?.coordinates.orEmpty().map { pt -> pt[1] to pt[0] }
                }

                // Framing bbox: [minLat, maxLat, minLng, maxLng].
                val bbox = remember(frameRing) {
                    if (frameRing.isEmpty()) listOf(0.0, 0.0, 0.0, 0.0)
                    else listOf(
                        frameRing.minOf { it.first }, frameRing.maxOf { it.first },
                        frameRing.minOf { it.second }, frameRing.maxOf { it.second },
                    )
                }

                // STABLE WORKING AREA: a true "contain" clamp. Per axis —
                // when the viewport (at this zoom) is larger than the padded
                // block frame, the center is PINNED to the frame's midpoint
                // (the block sits centered and cannot move at all); when
                // zoomed in tighter, the center may move only while the
                // viewport stays fully inside the padded frame. The boxed-in
                // area is always the view; drift is impossible.
                fun clampedCenter(lat: Double, lng: Double, z: Double, wPx: Float, hPx: Float): Pair<Double, Double> {
                    val midLat = (bbox[0] + bbox[1]) / 2.0
                    val midLng = (bbox[2] + bbox[3]) / 2.0
                    val lngScale = cos(midLat * PI / 180.0)
                    val mpp = 156543.03392 * lngScale / 2.0.pow(z + zoomBias)
                    val halfViewLat = hPx * mpp / 111_320.0 / 2.0
                    val halfViewLng = wPx * mpp / (111_320.0 * lngScale) / 2.0
                    // ~18% padding keeps edge vertices + their handles visible.
                    val padLat = (bbox[1] - bbox[0]) * 0.18
                    val padLng = (bbox[3] - bbox[2]) * 0.18
                    val frameLoLat = bbox[0] - padLat
                    val frameHiLat = bbox[1] + padLat
                    val frameLoLng = bbox[2] - padLng
                    val frameHiLng = bbox[3] + padLng
                    val clampedLat = if (frameHiLat - frameLoLat <= halfViewLat * 2) midLat
                        else lat.coerceIn(frameLoLat + halfViewLat, frameHiLat - halfViewLat)
                    val clampedLng = if (frameHiLng - frameLoLng <= halfViewLng * 2) midLng
                        else lng.coerceIn(frameLoLng + halfViewLng, frameHiLng - halfViewLng)
                    return clampedLat to clampedLng
                }

                // Fit the framing bbox with ~15% padding per side
                // (invert metersToPixels: z = log2(156543.03392·cos(lat)·px/spanMeters)).
                // Keyed on frameRing: the route arrives ASYNC, so the frame
                // must (re)run after blockBoundary lands, not just at mount.
                // PER-AXIS fit: the ENTIRE outer boundary must be featured,
                // as large as the viewport allows — north-south span fits the
                // viewport HEIGHT, east-west span fits the WIDTH, and the
                // tighter of the two wins. (The old min-dimension fit framed
                // tall blocks against the screen WIDTH, wasting margin and
                // drifting the block off-center.)
                // Each step has a home view: Perimeter = fully zoomed out
                // (boundary + neighborhood context); Exclusions = snug fit
                // (whole boundary, slightly closer). Switching steps snaps
                // to that step's home framing.
                LaunchedEffect(step) {
                    if (frameRing.isNotEmpty() && fitZoom > 13.0) {
                        zoom = if (step == EditStep.OUTER) (fitZoom - 1.5).coerceAtLeast(13.0) else fitZoom
                        centerLat = (bbox[0] + bbox[1]) / 2.0
                        centerLng = (bbox[2] + bbox[3]) / 2.0
                    }
                }
                LaunchedEffect(frameRing, boxWidthPx, boxHeightPx) {
                    if (boxWidthPx > 0 && boxHeightPx > 0 && frameRing.isNotEmpty()) {
                        centerLat = (bbox[0] + bbox[1]) / 2.0
                        centerLng = (bbox[2] + bbox[3]) / 2.0
                        val cosLat = cos(centerLat * PI / 180.0)
                        val spanNorth = maxOf((bbox[1] - bbox[0]) * 111_320.0, 30.0) * 1.25
                        val spanEast = maxOf((bbox[3] - bbox[2]) * 111_320.0 * cosLat, 30.0) * 1.25
                        val zoomForHeight = log2(156543.03392 * cosLat * boxHeightPx / spanNorth)
                        val zoomForWidth = log2(156543.03392 * cosLat * boxWidthPx / spanEast)
                        fitZoom = (minOf(zoomForHeight, zoomForWidth) - zoomBias).coerceIn(13.0, ZOOM_MAX)
                        zoom = if (step == EditStep.OUTER) (fitZoom - 1.5).coerceAtLeast(13.0) else fitZoom
                    }
                }

                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    enableGestures = false,
                    showBuildings = false,
                    instantCamera = true,
                    innerBoundaryFallback = false,
                    showGridLines = false,
                    // The LIVE geometry rendered by MapLibre itself — the
                    // exact pipeline the public map uses. The canvas on top
                    // adds only handles, hatching and drafts, so what admins
                    // edit IS what workers see.
                    routeCoordinates = if (outer.size >= 3) {
                        listOf(outer.map { (lat, lng) -> com.gcc.shared.model.Coordinates(lat = lat, lng = lng) })
                    } else emptyList(),
                    innerBoundaryRings = islands.filter { it.size >= 3 }.map { ring ->
                        (ring + ring.first()).map { (lat, lng) -> com.gcc.shared.model.Coordinates(lat = lat, lng = lng) }
                    },
                )

                // Overlay: draws boundaries AND owns ALL touch. One custom
                // pointer loop — 2+ pointers pinch-zoom/pan the map, 1 pointer
                // ONLY edits (vertex > midpoint > ring body > tap-deselect;
                // one-finger drags on empty map deliberately do nothing).
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(step, drawMode) {
                            fun projection() = BoundaryProjection(
                                centerLat, centerLng, zoom + zoomBias,
                                size.width.toFloat(), size.height.toFloat(),
                            )

                            /** Pan so the map follows the fingers (2-finger only). */
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

                            /** The rings a one-finger gesture may edit in this step. */
                            fun activeRings(): List<Ring> =
                                if (step == EditStep.OUTER) listOf(outer).filter { it.size >= 3 }
                                else islands

                            fun activeSelected(): Int? =
                                if (step == EditStep.OUTER) (if (outer.size >= 3) 0 else null)
                                else selectedIsland

                            fun setActiveRing(index: Int, ring: Ring) {
                                if (step == EditStep.OUTER) outer = ring
                                else islands = islands.toMutableList().also { it[index] = ring }
                            }

                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downPos = down.position
                                val slop = viewConfiguration.touchSlop
                                val vertexHitPx = 24.dp.toPx()
                                val midpointHitPx = 22.dp.toPx()
                                val edgeSlopPx = 16.dp.toPx()

                                var target = DragTarget.NONE
                                var dragVertex = -1
                                var tappedRing: Int? = null
                                var preOuter: Ring? = null
                                var preIslands: List<Ring>? = null
                                var edited = false
                                var pinched = false
                                var pastSlop = false

                                fun rememberPre() {
                                    preOuter = outer
                                    preIslands = islands
                                }

                                // ── Classify the pointer-down ──────────────
                                val p0 = projection()
                                val selIdx = activeSelected()
                                val sel = if (drawMode) null else selIdx?.let { activeRings().getOrNull(it) }
                                if (sel != null && selIdx != null) {
                                    val pts = sel.map { (la, ln) -> p0.project(la, ln) }
                                    // 1) Vertex handle of the selected ring.
                                    val vi = pts.indices
                                        .minByOrNull { (pts[it] - downPos).getDistance() }
                                        ?.takeIf { (pts[it] - downPos).getDistance() <= vertexHitPx }
                                    if (vi != null) {
                                        target = DragTarget.VERTEX
                                        dragVertex = vi
                                        rememberPre()
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
                                            rememberPre()
                                            val (mLat, mLng) = p0.unproject(mids[mi])
                                            setActiveRing(
                                                selIdx,
                                                sel.toMutableList().also { it.add(mi + 1, mLat to mLng) },
                                            )
                                            target = DragTarget.VERTEX
                                            dragVertex = mi + 1
                                            selectedVertex = mi + 1
                                            edited = true
                                        }
                                    }
                                }
                                if (target == DragTarget.NONE && !drawMode) {
                                    // 3) Ring body (generous hit slop around edges).
                                    fun hits(ring: Ring): Boolean {
                                        val pts = ring.map { (la, ln) -> p0.project(la, ln) }
                                        return screenPointInPolygon(downPos, pts) ||
                                            distanceToPolygonEdge(downPos, pts) <= edgeSlopPx
                                    }
                                    val rings = activeRings()
                                    val hitIdx = when {
                                        selIdx != null && rings.getOrNull(selIdx)?.let(::hits) == true -> selIdx
                                        else -> rings.indices.lastOrNull { hits(rings[it]) }
                                    }
                                    if (hitIdx != null) {
                                        tappedRing = hitIdx
                                        if (hitIdx == selIdx) {
                                            // Drag inside the selected ring moves it.
                                            target = DragTarget.RING
                                            rememberPre()
                                        }
                                    }
                                    // 4) Otherwise: empty map → a one-finger drag
                                    // does NOTHING (map moves with two fingers);
                                    // a tap deselects.
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
                                                preOuter?.let { po ->
                                                    preIslands?.let { pi -> pushUndoSnapshot(po, pi) }
                                                }
                                                edited = false
                                                preOuter = null
                                                preIslands = null
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
                                        val zoomFloor = if (step == EditStep.ISLANDS) fitZoom - 0.5 else fitZoom - 2.5
                                        val newZoom = if (oldDist > 8f && newDist > 8f) {
                                            (zoom + log2(newDist.toDouble() / oldDist))
                                                .coerceIn(minOf(zoomFloor, ZOOM_MAX), ZOOM_MAX)
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
                                            val si = activeSelected()
                                            if (si != null && dragVertex < (activeRings().getOrNull(si)?.size ?: 0)) {
                                                val (la, ln) = projection().unproject(change.position)
                                                setActiveRing(
                                                    si,
                                                    activeRings()[si].toMutableList()
                                                        .also { it[dragVertex] = la to ln },
                                                )
                                                edited = true
                                            }
                                        }
                                        target == DragTarget.RING && pastSlop -> {
                                            // Ring follows the finger.
                                            val si = activeSelected()
                                            val rings = activeRings()
                                            if (si != null && si < rings.size && delta != Offset.Zero) {
                                                val cur = projection()
                                                val dLat = -delta.y * cur.metersPerPixel / 111_320.0
                                                val dLng = delta.x * cur.metersPerPixel / (111_320.0 * cur.lngScale)
                                                setActiveRing(
                                                    si,
                                                    rings[si].map { (la, ln) -> (la + dLat) to (ln + dLng) },
                                                )
                                                edited = true
                                            }
                                        }
                                        // DragTarget.NONE: one-finger drag on empty
                                        // map is intentionally inert.
                                    }
                                    change.consume()
                                }

                                // ── Gesture end ────────────────────────────
                                if (edited) {
                                    // One undo entry for the whole gesture.
                                    preOuter?.let { po ->
                                        preIslands?.let { pi -> pushUndoSnapshot(po, pi) }
                                    }
                                    if (step == EditStep.ISLANDS) {
                                        selectedIsland?.let { si -> islands.getOrNull(si)?.let(::warnIfOutside) }
                                    }
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
                                        tappedRing != null && step == EditStep.ISLANDS -> {
                                            if (selectedIsland != tappedRing) {
                                                selectedIsland = tappedRing
                                            }
                                            selectedVertex = null
                                        }
                                        else -> {
                                            if (step == EditStep.ISLANDS) selectedIsland = null
                                            selectedVertex = null
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    val proj = BoundaryProjection(centerLat, centerLng, zoom + zoomBias, size.width, size.height)

                    fun ringPath(ring: Ring): Path {
                        val path = Path()
                        ring.forEachIndexed { i, (lat, lng) ->
                            val p = proj.project(lat, lng)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        path.close()
                        return path
                    }

                    fun drawHandles(ring: Ring, accent: Color) {
                        val pts = ring.map { (lat, lng) -> proj.project(lat, lng) }
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
                            drawCircle(accent, radius = (if (isSel) 5.dp else 4.dp).toPx(), center = p)
                        }
                    }

                    if (outer.size >= 3) {
                        // Step-aware exterior mask (one even-odd path: full-
                        // canvas rect + boundary ring as the hole; blend-mode-
                        // free, robust on iOS Skia):
                        //  · OUTER — barely-there dim: reshaping the perimeter
                        //    needs the surrounding streets and lots visible.
                        //  · ISLANDS — heavy mask: the working surface is ONLY
                        //    the block interior.
                        val shade = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            outer.forEachIndexed { i, (lat, lng) ->
                                val p = proj.project(lat, lng)
                                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                            close()
                        }
                        // PERIMETER step: no mask at all — match the main-map
                        // block view (green loop over the plain basemap).
                        if (step == EditStep.ISLANDS) {
                            drawPath(shade, color = Color.Black.copy(alpha = 0.72f))
                        }

                        // The perimeter LINE is MapLibre's job (the live ring
                        // is fed to the map's route layers — identical to the
                        // public display). The canvas adds only the editing
                        // affordances: an amber accent while edits are
                        // unsaved, and the corner/midpoint handles.
                        val outerActive = step == EditStep.OUTER && !drawMode
                        if (outerDirty) {
                            drawPath(
                                ringPath(outer),
                                color = IslandAmberBright,
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                        if (outerActive) drawHandles(outer, GccGreen)
                    }

                    // Exclusion zones — ONLY on the exclusions step (the
                    // perimeter step stays clean, like the main-map view).
                    // Visual language: grey perimeter + grey diagonal hatch
                    // = "excluded from the scan area".
                    if (step == EditStep.ISLANDS) {
                        islands.forEachIndexed { i, island ->
                            if (island.size < 3) return@forEachIndexed
                            val selected = i == selectedIsland
                            val path = ringPath(island)
                            val grey = if (selected) ExclusionGreyDark else ExclusionGrey
                            // Line + fill come from the map's grey exclusion
                            // layers (same pipeline as the public map); the
                            // canvas contributes the diagonal hatch language.
                            clipPath(path) {
                                val hatchStep = 12.dp.toPx()
                                val h = size.height
                                var d = -h
                                while (d < size.width) {
                                    drawLine(
                                        color = grey.copy(alpha = if (selected) 0.6f else 0.45f),
                                        start = Offset(d, 0f),
                                        end = Offset(d + h, h),
                                        strokeWidth = 1.5.dp.toPx(),
                                    )
                                    d += hatchStep
                                }
                            }
                            if (selected) {
                                drawPath(path, color = grey, style = Stroke(width = 3.dp.toPx()))
                                drawHandles(island, ExclusionGreyDark)
                            }
                        }
                    }

                    // In-progress draft ring — points + connecting lines.
                    if (draft.isNotEmpty()) {
                        val accent = if (step == EditStep.OUTER) GccGreen else GccSky
                        val pts = draft.map { (lat, lng) -> proj.project(lat, lng) }
                        for (i in 0 until pts.size - 1) {
                            drawLine(color = accent, start = pts[i], end = pts[i + 1], strokeWidth = 2.dp.toPx())
                        }
                        if (pts.size >= 3) {
                            drawLine(
                                color = accent.copy(alpha = 0.4f),
                                start = pts.last(),
                                end = pts.first(),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                            // First point becomes the close target — ring it.
                            drawCircle(GccGreen, radius = 10.dp.toPx(), center = pts.first(), style = Stroke(width = 2.5.dp.toPx()))
                        }
                        pts.forEach { p ->
                            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = p)
                            drawCircle(color = accent, radius = 3.5.dp.toPx(), center = p)
                        }
                    }
                }

                // Floating "remove point" chip near the tap-selected vertex.
                val chipVertex = selectedVertex
                val chipRing: Ring? = when {
                    drawMode || chipVertex == null -> null
                    step == EditStep.OUTER -> outer.takeIf { it.size >= 3 }
                    else -> selectedIsland?.let { islands.getOrNull(it) }
                }
                if (chipRing != null && chipVertex != null && chipVertex < chipRing.size) {
                    val pt = chipRing[chipVertex]
                    val pos = BoundaryProjection(centerLat, centerLng, zoom + zoomBias, boxWidthPx, boxHeightPx)
                        .project(pt.first, pt.second)
                    val chipW = with(density) { 150.dp.toPx() }
                    val chipH = with(density) { 40.dp.toPx() }
                    val gap = with(density) { 18.dp.toPx() }
                    val x = (pos.x - chipW / 2f).coerceIn(8f, (boxWidthPx - chipW - 8f).coerceAtLeast(8f))
                    val y = (pos.y - chipH - gap).coerceAtLeast(8f)
                    val canRemove = chipRing.size > 3
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
                                        pushUndoSnapshot()
                                        val newRing = chipRing.toMutableList().also { it.removeAt(chipVertex) }
                                        if (step == EditStep.OUTER) {
                                            outer = newRing
                                        } else {
                                            val ci = selectedIsland
                                            if (ci != null) {
                                                islands = islands.toMutableList().also { it[ci] = newRing }
                                                warnIfOutside(newRing)
                                            }
                                        }
                                        selectedVertex = null
                                    } else Modifier
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                // Zoom nudge buttons — pinch fallback for precision work.
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fun nudgeZoom(delta: Double) {
                        // Islands: out just far enough for the outer boundary
                        // + a little context. Outer: more room, for reshaping
                        // the perimeter against the neighborhood.
                        val zoomFloor = if (step == EditStep.ISLANDS) fitZoom - 0.5 else fitZoom - 2.5
                        zoom = (zoom + delta).coerceIn(minOf(zoomFloor, ZOOM_MAX), ZOOM_MAX)
                        val c = clampedCenter(centerLat, centerLng, zoom, boxWidthPx, boxHeightPx)
                        centerLat = c.first
                        centerLng = c.second
                    }
                    ZoomButton("+") { nudgeZoom(0.5) }
                    ZoomButton("−") { nudgeZoom(-0.5) }
                    ZoomButton("⊡") {
                        // Re-frame: back to the step's home view.
                        zoom = if (step == EditStep.OUTER) (fitZoom - 1.5).coerceAtLeast(13.0) else fitZoom
                        centerLat = (bbox[0] + bbox[1]) / 2.0
                        centerLng = (bbox[2] + bbox[3]) / 2.0
                    }
                }

                // Draw-mode banner.
                if (drawMode) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                    ) {
                        Text(
                            when {
                                step == EditStep.OUTER && draft.isEmpty() ->
                                    "Draw the block's outer edge: tap each corner · tap the first point to close"
                                step == EditStep.OUTER ->
                                    "Perimeter: ${draft.size} corner${if (draft.size == 1) "" else "s"} · tap the first point to close"
                                draft.isEmpty() -> "Tap the map to place points · tap the first point to close"
                                else -> "Tap to place points (${draft.size}) · tap the first point to close"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }

                // First-open hint: teach the gesture contract once.
                if (initialized && !drawMode && !hintDismissed) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp, start = 16.dp, end = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                "One finger edits · two fingers move the map. " +
                                    if (step == EditStep.OUTER) "Drag the green corners to reshape the block."
                                    else "◫ Corridor makes one exclusion ring matching the route's band — or draw exclusion zones per lot.",
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
private fun ToolbarLabel(text: String, color: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color ?: Color.Unspecified,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun StepChip(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) GccSkyDark else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick),
        shadowElevation = if (active) 2.dp else 0.dp,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                active -> Color.White
                enabled -> TextSubtle
                else -> TextSubtle.copy(alpha = 0.4f)
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
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
    step: EditStep,
    drawMode: Boolean,
    draftCount: Int,
    onStartDraw: () -> Unit,
    onCloseDraft: () -> Unit,
    onCancelDraft: () -> Unit,
    hasOuter: Boolean,
    islandCount: Int,
    selectedIndex: Int?,
    onDeleteIsland: () -> Unit,
    onClearIslands: () -> Unit,
    onCorridor: () -> Unit,
    suggesting: Boolean,
    onSuggest: () -> Unit,
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
                        color = if (step == EditStep.OUTER) GccGreen else GccSky,
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
                if (step == EditStep.ISLANDS && selectedIndex != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Zone ${selectedIndex + 1} of ${islandCount}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = IslandAmberBright,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDeleteIsland) {
                            Text("Delete zone", color = GccError)
                        }
                    }
                }
                if (step == EditStep.ISLANDS) {
                    // Four actions in one row: tight padding + single-line
                    // labels (default M3 padding wrapped 'Corridor'/'Clear'
                    // into two ugly lines on phone widths).
                    val tightPad = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onCorridor,
                            enabled = hasOuter,
                            modifier = Modifier.weight(1.15f),
                            contentPadding = tightPad,
                            colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        ) { ToolbarLabel("Corridor", Color.White) }
                        Button(
                            onClick = onStartDraw,
                            modifier = Modifier.weight(1f),
                            contentPadding = tightPad,
                            colors = ButtonDefaults.buttonColors(containerColor = GccSky),
                        ) { ToolbarLabel("+ Zone", Color.White) }
                        OutlinedButton(
                            onClick = onSuggest,
                            enabled = !suggesting && hasOuter,
                            modifier = Modifier.weight(0.9f),
                            contentPadding = tightPad,
                        ) {
                            if (suggesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                ToolbarLabel("✨ Auto")
                            }
                        }
                        OutlinedButton(
                            onClick = onClearIslands,
                            enabled = islandCount > 0,
                            modifier = Modifier.weight(0.8f),
                            contentPadding = tightPad,
                        ) { ToolbarLabel("Clear") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartDraw,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = GccSky),
                        ) { Text(if (hasOuter) "Redraw edge" else "Draw edge", color = Color.White) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Serialize the outer ring to a closed GeoJSON Polygon ([lng, lat] order). */
private fun ringToPolygon(ring: Ring): GeoJSONPolygon =
    GeoJSONPolygon(
        coordinates = listOf(
            ring.map { (lat, lng) -> listOf(lng, lat) }.let { it + listOf(it.first()) },
        ),
    )
