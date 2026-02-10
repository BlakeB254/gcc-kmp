package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.ServiceAreas
import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.*

class ServiceAreaService {

    suspend fun getAll(limit: Int = 50, offset: Long = 0): List<ServiceArea> = dbQuery {
        ServiceAreas.selectAll()
            .orderBy(ServiceAreas.name, SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { it.toServiceArea() }
    }

    suspend fun getById(id: Int): ServiceArea? = dbQuery {
        ServiceAreas.selectAll().where { ServiceAreas.id eq id }
            .firstOrNull()?.toServiceArea()
    }

    suspend fun getActive(): List<ServiceArea> = dbQuery {
        ServiceAreas.selectAll()
            .where { ServiceAreas.status eq "active" }
            .orderBy(ServiceAreas.name, SortOrder.ASC)
            .map { it.toServiceArea() }
    }

    suspend fun count(): Long = dbQuery {
        ServiceAreas.selectAll().count()
    }
}

fun ResultRow.toServiceArea(): ServiceArea = ServiceArea(
    id = this[ServiceAreas.id],
    name = this[ServiceAreas.name],
    city = this[ServiceAreas.city],
    state = this[ServiceAreas.state],
    boundary = this[ServiceAreas.boundary],
    mapCenter = this[ServiceAreas.mapCenter],
    mapZoom = this[ServiceAreas.mapZoom],
    status = enumValueOfOrDefault(this[ServiceAreas.status]),
    totalBlocks = this[ServiceAreas.totalBlocks],
    activeWorkers = this[ServiceAreas.activeWorkers],
    createdAt = this[ServiceAreas.createdAt],
    updatedAt = this[ServiceAreas.updatedAt]
)
