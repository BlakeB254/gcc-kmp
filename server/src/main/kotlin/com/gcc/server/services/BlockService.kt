package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Blocks
import com.gcc.server.db.tables.RouteCheckpoints
import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.*

class BlockService {

    suspend fun getAll(
        status: BlockStatus? = null,
        serviceAreaId: Int? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Block> = dbQuery {
        val query = Blocks.selectAll()
        status?.let { query.andWhere { Blocks.status eq it.name.lowercase() } }
        serviceAreaId?.let { query.andWhere { Blocks.serviceAreaId eq it } }

        query
            .orderBy(Blocks.code, SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { it.toBlock() }
    }

    suspend fun getById(id: Int): Block? = dbQuery {
        Blocks.selectAll().where { Blocks.id eq id }
            .firstOrNull()?.toBlock()
    }

    suspend fun count(): Long = dbQuery {
        Blocks.selectAll().count()
    }

    suspend fun getFunded(): List<Block> =
        getAll(status = BlockStatus.FUNDED)

    suspend fun getAvailable(): List<Block> =
        getAll(status = BlockStatus.AVAILABLE)
}

fun ResultRow.toBlock(): Block = Block(
    id = this[Blocks.id],
    code = this[Blocks.code],
    name = this[Blocks.name],
    serviceAreaId = this[Blocks.serviceAreaId],
    status = enumValueOfOrDefault(this[Blocks.status]),
    boundary = this[Blocks.boundary],
    center = this[Blocks.center],
    fundingGoal = this[Blocks.fundingGoal],
    fundingCurrent = this[Blocks.fundingCurrent],
    sponsorId = this[Blocks.sponsorId],
    checkpoints = emptyList(), // loaded separately if needed
    createdAt = this[Blocks.createdAt],
    updatedAt = this[Blocks.updatedAt]
)
