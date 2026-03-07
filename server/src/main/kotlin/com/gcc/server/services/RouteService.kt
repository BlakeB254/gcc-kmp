package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.RouteCheckpoints
import com.gcc.server.db.tables.Routes
import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.*

class RouteService {

    suspend fun getAll(
        status: RouteStatus? = null,
        serviceAreaId: Int? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Route> = dbQuery {
        val query = Routes.selectAll()
        status?.let { query.andWhere { Routes.status eq it.name.lowercase() } }
        serviceAreaId?.let { query.andWhere { Routes.serviceAreaId eq it } }

        val routes = query
            .orderBy(Routes.displayName, SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { it.toRoute() }

        // Batch-load checkpoints for all routes
        if (routes.isNotEmpty()) {
            val routeIds = routes.map { it.id }
            val checkpointsByRoute = RouteCheckpoints.selectAll()
                .where { RouteCheckpoints.routeId inList routeIds }
                .orderBy(RouteCheckpoints.sequenceOrder, SortOrder.ASC)
                .groupBy(
                    { it[RouteCheckpoints.routeId] },
                    { it.toCheckpoint() }
                )

            routes.map { route ->
                route.copy(checkpoints = checkpointsByRoute[route.id] ?: emptyList())
            }
        } else {
            routes
        }
    }

    suspend fun getById(id: Int): Route? = dbQuery {
        val route = Routes.selectAll().where { Routes.id eq id }
            .firstOrNull()?.toRoute() ?: return@dbQuery null

        val checkpoints = RouteCheckpoints.selectAll()
            .where { RouteCheckpoints.routeId eq id }
            .orderBy(RouteCheckpoints.sequenceOrder, SortOrder.ASC)
            .map { it.toCheckpoint() }

        route.copy(checkpoints = checkpoints)
    }

    suspend fun count(): Long = dbQuery {
        Routes.selectAll().count()
    }

    suspend fun getPublished(limit: Int = 50): List<Route> =
        getAll(status = RouteStatus.PUBLISHED, limit = limit)
}

fun ResultRow.toRoute(): Route = Route(
    id = this[Routes.id],
    code = this[Routes.code],
    displayName = this[Routes.displayName],
    serviceAreaId = this[Routes.serviceAreaId],
    geometry = this[Routes.geometry],
    status = enumValueOfOrDefault(this[Routes.status]),
    distanceMeters = this[Routes.lengthMeters]?.toDoubleOrNull(),
    estimatedDurationMinutes = this[Routes.estimatedMinutes]?.toIntOrNull(),
    payoutAmount = this[Routes.payoutAmount]?.toIntOrNull(),
    checkpoints = emptyList(), // populated by service after batch-loading
    imageUrl = this[Routes.imageUrl],
    createdAt = this[Routes.createdAt],
    updatedAt = this[Routes.updatedAt]
)

fun ResultRow.toCheckpoint(): RouteCheckpoint {
    val coords = this[RouteCheckpoints.coordinates]
    return RouteCheckpoint(
        id = this[RouteCheckpoints.id],
        name = this[RouteCheckpoints.name],
        sequenceOrder = this[RouteCheckpoints.sequenceOrder],
        coordinates = Coordinates(
            lng = coords.getOrElse(0) { 0.0 },
            lat = coords.getOrElse(1) { 0.0 }
        ),
        photoRequired = this[RouteCheckpoints.photoRequired],
        geofenceRadius = this[RouteCheckpoints.geofenceRadius]?.toDoubleOrNull() ?: 15.0,
        type = enumValueOfOrDefault(this[RouteCheckpoints.type]),
        imageUrl = this[RouteCheckpoints.imageUrl]
    )
}
