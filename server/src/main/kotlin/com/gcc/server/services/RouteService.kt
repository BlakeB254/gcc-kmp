package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
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

        query
            .orderBy(Routes.displayName, SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { it.toRoute() }
    }

    suspend fun getById(id: Int): Route? = dbQuery {
        Routes.selectAll().where { Routes.id eq id }
            .firstOrNull()?.toRoute()
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
    imageUrl = this[Routes.imageUrl],
    imageLocationLat = this[Routes.imageLocationLat],
    imageLocationLng = this[Routes.imageLocationLng],
    createdAt = this[Routes.createdAt],
    updatedAt = this[Routes.updatedAt]
)
