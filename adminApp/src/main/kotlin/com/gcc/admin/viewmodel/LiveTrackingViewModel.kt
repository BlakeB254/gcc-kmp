package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.LiveRouteSession
import com.gcc.shared.model.RouteMarker
import com.gcc.shared.model.RouteMarkerType
import com.gcc.shared.model.RouteMessage
import com.gcc.shared.model.RouteSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * ViewModel for the Live Tracking screen.
 *
 * Manages active route-session state, session selection, and in-app
 * messaging between admins and workers. Currently uses mock data while
 * backend API endpoints are being developed.
 */
class LiveTrackingViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _activeSessions = MutableStateFlow<List<LiveRouteSession>>(emptyList())
    val activeSessions: StateFlow<List<LiveRouteSession>> = _activeSessions.asStateFlow()

    private val _selectedSession = MutableStateFlow<LiveRouteSession?>(null)
    val selectedSession: StateFlow<LiveRouteSession?> = _selectedSession.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Next auto-increment ID for locally-created messages. */
    private var nextMessageId = 1000

    init {
        loadActiveSessions()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Loads all currently active route sessions.
     * TODO: Replace mock data with `api.getLiveRouteSessions()` once available.
     */
    fun loadActiveSessions() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _activeSessions.value = createMockSessions()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load active sessions"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Select a session by its ID for the detail panel. */
    fun selectSession(sessionId: Int) {
        _selectedSession.value = _activeSessions.value.find { it.id == sessionId }
    }

    /** Update the compose text-field state for the message input. */
    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    /**
     * Send a message from the admin to the given session.
     * Appends the message to local state immediately (optimistic UI).
     * TODO: Replace with `api.sendRouteMessage(sessionId, content)`.
     */
    fun sendMessage(sessionId: Int, content: String) {
        if (content.isBlank()) return
        val message = RouteMessage(
            id = nextMessageId++,
            routeSessionId = sessionId,
            senderId = 0, // admin user
            senderName = "Admin",
            senderRole = "admin",
            content = content.trim(),
            createdAt = Clock.System.now(),
        )

        _activeSessions.update { sessions ->
            sessions.map { session ->
                if (session.id == sessionId) {
                    session.copy(messages = session.messages + message)
                } else {
                    session
                }
            }
        }

        // Keep the selected session in sync
        _selectedSession.update { selected ->
            if (selected?.id == sessionId) {
                _activeSessions.value.find { it.id == sessionId }
            } else {
                selected
            }
        }

        _messageText.value = ""
    }

    /**
     * Re-fetch a specific session's data.
     * TODO: Replace with `api.getLiveRouteSession(sessionId)`.
     */
    fun refreshSession(sessionId: Int) {
        scope.launch {
            _error.value = null
            try {
                // Currently just re-sync from the local list
                _selectedSession.value = _activeSessions.value.find { it.id == sessionId }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh session"
            }
        }
    }

    // ── Mock Data ───────────────────────────────────────────────────────────

    private fun createMockSessions(): List<LiveRouteSession> {
        val now = Clock.System.now()

        return listOf(
            LiveRouteSession(
                id = 1,
                routeId = 12,
                workerId = 101,
                workerName = "Marcus Johnson",
                routeCode = "ENGWD-12",
                routeDisplayName = "Englewood West District",
                status = RouteSessionStatus.ACTIVE,
                currentPosition = Coordinates(lng = -87.6685, lat = 41.7796),
                checkpointsCompleted = 5,
                checkpointsTotal = 12,
                markers = listOf(
                    RouteMarker(
                        id = 1,
                        routeSessionId = 1,
                        workerId = 101,
                        type = RouteMarkerType.NOTE,
                        coordinates = Coordinates(lng = -87.6690, lat = 41.7800),
                        note = "Large amount of debris near the corner of 63rd and Halsted",
                        createdAt = now - 45.minutes,
                    ),
                    RouteMarker(
                        id = 2,
                        routeSessionId = 1,
                        workerId = 101,
                        type = RouteMarkerType.PHOTO,
                        coordinates = Coordinates(lng = -87.6675, lat = 41.7790),
                        note = "Before/after: cleared lot at 6421 S Green St",
                        mediaUrls = listOf("https://media.gcc.local/photos/marker-2.jpg"),
                        createdAt = now - 30.minutes,
                    ),
                    RouteMarker(
                        id = 3,
                        routeSessionId = 1,
                        workerId = 101,
                        type = RouteMarkerType.ISSUE,
                        coordinates = Coordinates(lng = -87.6680, lat = 41.7795),
                        note = "Overflowing dumpster blocking sidewalk — needs city pickup",
                        createdAt = now - 15.minutes,
                    ),
                ),
                messages = listOf(
                    RouteMessage(
                        id = 1,
                        routeSessionId = 1,
                        senderId = 101,
                        senderName = "Marcus Johnson",
                        senderRole = "worker",
                        content = "Starting the route now. Weather is clear.",
                        createdAt = now - 1.hours,
                    ),
                    RouteMessage(
                        id = 2,
                        routeSessionId = 1,
                        senderId = 0,
                        senderName = "Admin",
                        senderRole = "admin",
                        content = "Great, be safe out there. Let me know if you need supplies restocked.",
                        createdAt = now - 55.minutes,
                    ),
                    RouteMessage(
                        id = 3,
                        routeSessionId = 1,
                        senderId = 101,
                        senderName = "Marcus Johnson",
                        senderRole = "worker",
                        content = "Found a large illegal dump near checkpoint 4. Flagging it.",
                        createdAt = now - 20.minutes,
                    ),
                ),
                startedAt = now - 1.hours,
                lastUpdateAt = now - 2.minutes,
            ),
            LiveRouteSession(
                id = 2,
                routeId = 8,
                workerId = 205,
                workerName = "Aaliyah Williams",
                routeCode = "AUBPK-08",
                routeDisplayName = "Auburn Park Residential",
                status = RouteSessionStatus.ACTIVE,
                currentPosition = Coordinates(lng = -87.6340, lat = 41.7480),
                checkpointsCompleted = 9,
                checkpointsTotal = 10,
                markers = listOf(
                    RouteMarker(
                        id = 4,
                        routeSessionId = 2,
                        workerId = 205,
                        type = RouteMarkerType.BOUNTY_RESPONSE,
                        coordinates = Coordinates(lng = -87.6335, lat = 41.7485),
                        note = "Completed bounty: cleared abandoned tires from vacant lot",
                        bountyPromptId = 42,
                        createdAt = now - 25.minutes,
                    ),
                    RouteMarker(
                        id = 5,
                        routeSessionId = 2,
                        workerId = 205,
                        type = RouteMarkerType.PHOTO,
                        coordinates = Coordinates(lng = -87.6345, lat = 41.7475),
                        note = "Final checkpoint area cleaned",
                        mediaUrls = listOf("https://media.gcc.local/photos/marker-5.jpg"),
                        createdAt = now - 10.minutes,
                    ),
                ),
                messages = listOf(
                    RouteMessage(
                        id = 4,
                        routeSessionId = 2,
                        senderId = 205,
                        senderName = "Aaliyah Williams",
                        senderRole = "worker",
                        content = "Almost done! One checkpoint left.",
                        createdAt = now - 12.minutes,
                    ),
                ),
                startedAt = now - 2.hours,
                lastUpdateAt = now - 5.minutes,
            ),
            LiveRouteSession(
                id = 3,
                routeId = 21,
                workerId = 312,
                workerName = "DeShawn Carter",
                routeCode = "WDLWN-21",
                routeDisplayName = "Woodlawn Commercial Strip",
                status = RouteSessionStatus.PAUSED,
                currentPosition = Coordinates(lng = -87.6045, lat = 41.7810),
                checkpointsCompleted = 3,
                checkpointsTotal = 8,
                markers = listOf(
                    RouteMarker(
                        id = 6,
                        routeSessionId = 3,
                        workerId = 312,
                        type = RouteMarkerType.VIDEO,
                        coordinates = Coordinates(lng = -87.6050, lat = 41.7815),
                        note = "Video of graffiti removal in progress",
                        mediaUrls = listOf("https://media.gcc.local/videos/marker-6.mp4"),
                        createdAt = now - 40.minutes,
                    ),
                    RouteMarker(
                        id = 7,
                        routeSessionId = 3,
                        workerId = 312,
                        type = RouteMarkerType.NOTE,
                        coordinates = Coordinates(lng = -87.6040, lat = 41.7805),
                        note = "Taking a break — very hot today. Will resume in 15 min.",
                        createdAt = now - 10.minutes,
                    ),
                ),
                messages = listOf(
                    RouteMessage(
                        id = 5,
                        routeSessionId = 3,
                        senderId = 312,
                        senderName = "DeShawn Carter",
                        senderRole = "worker",
                        content = "Pausing for a water break. It's 95 out here.",
                        createdAt = now - 10.minutes,
                    ),
                    RouteMessage(
                        id = 6,
                        routeSessionId = 3,
                        senderId = 0,
                        senderName = "Admin",
                        senderRole = "admin",
                        content = "Take your time. Stay hydrated!",
                        createdAt = now - 8.minutes,
                    ),
                ),
                startedAt = now - 1.5.hours,
                lastUpdateAt = now - 8.minutes,
            ),
        )
    }
}
