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

    suspend fun getServiceAreaForLocation(lat: Double, lng: Double): ServiceArea? = dbQuery {
        ServiceAreas.selectAll()
            .where { ServiceAreas.status eq "active" }
            .map { it.toServiceArea() }
            .find { area ->
                area.boundary?.let { polygon ->
                    pointInPolygon(lat, lng, polygon)
                } ?: false
            }
    }

    private fun pointInPolygon(lat: Double, lng: Double, polygon: GeoJSONPolygon): Boolean {
        val ring = polygon.coordinates.firstOrNull() ?: return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].getOrNull(1) ?: continue
            val xi = ring[i].getOrNull(0) ?: continue
            val yj = ring[j].getOrNull(1) ?: continue
            val xj = ring[j].getOrNull(0) ?: continue

            if (((yi > lat) != (yj > lat)) &&
                (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
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
