package com.gcc.app.ui.map

import com.gcc.shared.model.BlockStatus

/**
 * A fundable city block within a service area.
 *
 * Blocks are geographic rectangles (typically bounded by 4 street intersections)
 * that can be individually funded by sponsors and cleaned by workers.
 *
 * @property polygon List of (lat, lng) pairs defining the block outline (4-6 vertices).
 * @property fundingGoalCents Target funding amount in cents.
 * @property fundingCurrentCents Current funding amount in cents.
 */
data class MapBlock(
    val id: Int,
    val code: String,
    val name: String,
    val polygon: List<Pair<Double, Double>>,
    val fundingGoalCents: Int,
    val fundingCurrentCents: Int,
    val status: BlockStatus,
)

/**
 * 10 real North Lawndale blocks based on the Chicago street grid.
 *
 * Each block is roughly one city block (~1/8 mile × ~1/16 mile) defined by
 * four street intersections. Coordinates derived from the demo route checkpoints
 * and real Chicago geography.
 *
 * Block numbering: NL-001 through NL-010, covering the core of North Lawndale
 * from Pulaski (west) to Kedzie (east), Roosevelt (north) to Cermak (south).
 */
val northLawndaleBlocks = listOf(
    // NL-001: Pulaski & Roosevelt — fully funded showcase block
    MapBlock(
        id = 1,
        code = "NL-001",
        name = "Pulaski & Roosevelt",
        polygon = listOf(
            41.8695 to -87.7290,  // NW corner
            41.8695 to -87.7230,  // NE corner
            41.8665 to -87.7230,  // SE corner
            41.8665 to -87.7290,  // SW corner
        ),
        fundingGoalCents = 500000,
        fundingCurrentCents = 500000,
        status = BlockStatus.FUNDED,
    ),

    // NL-002: Homan & Ogden — work in progress
    MapBlock(
        id = 2,
        code = "NL-002",
        name = "Homan & Ogden",
        polygon = listOf(
            41.8570 to -87.7135,  // NW
            41.8570 to -87.7075,  // NE
            41.8540 to -87.7075,  // SE
            41.8540 to -87.7135,  // SW
        ),
        fundingGoalCents = 350000,
        fundingCurrentCents = 280000,
        status = BlockStatus.IN_PROGRESS,
    ),

    // NL-003: Kedzie & Cermak — open for funding
    MapBlock(
        id = 3,
        code = "NL-003",
        name = "Kedzie & Cermak",
        polygon = listOf(
            41.8520 to -87.7075,  // NW
            41.8520 to -87.7015,  // NE
            41.8490 to -87.7015,  // SE
            41.8490 to -87.7075,  // SW
        ),
        fundingGoalCents = 400000,
        fundingCurrentCents = 120000,
        status = BlockStatus.AVAILABLE,
    ),

    // NL-004: Central Park & Fillmore
    MapBlock(
        id = 4,
        code = "NL-004",
        name = "Central Park & Fillmore",
        polygon = listOf(
            41.8640 to -87.7195,  // NW
            41.8640 to -87.7135,  // NE
            41.8610 to -87.7135,  // SE
            41.8610 to -87.7195,  // SW
        ),
        fundingGoalCents = 300000,
        fundingCurrentCents = 75000,
        status = BlockStatus.AVAILABLE,
    ),

    // NL-005: Independence & 16th — completed block
    MapBlock(
        id = 5,
        code = "NL-005",
        name = "Independence & 16th",
        polygon = listOf(
            41.8605 to -87.7030,  // NW
            41.8605 to -87.6970,  // NE
            41.8575 to -87.6970,  // SE
            41.8575 to -87.7030,  // SW
        ),
        fundingGoalCents = 250000,
        fundingCurrentCents = 250000,
        status = BlockStatus.COMPLETED,
    ),

    // NL-006: Springfield & 13th
    MapBlock(
        id = 6,
        code = "NL-006",
        name = "Springfield & 13th",
        polygon = listOf(
            41.8660 to -87.7230,  // NW
            41.8660 to -87.7170,  // NE
            41.8630 to -87.7170,  // SE
            41.8630 to -87.7230,  // SW
        ),
        fundingGoalCents = 350000,
        fundingCurrentCents = 50000,
        status = BlockStatus.AVAILABLE,
    ),

    // NL-007: Kostner & Roosevelt — fully funded
    MapBlock(
        id = 7,
        code = "NL-007",
        name = "Kostner & Roosevelt",
        polygon = listOf(
            41.8695 to -87.7370,  // NW
            41.8695 to -87.7310,  // NE
            41.8665 to -87.7310,  // SE
            41.8665 to -87.7370,  // SW
        ),
        fundingGoalCents = 450000,
        fundingCurrentCents = 450000,
        status = BlockStatus.FUNDED,
    ),

    // NL-008: Lawndale & Grenshaw
    MapBlock(
        id = 8,
        code = "NL-008",
        name = "Lawndale & Grenshaw",
        polygon = listOf(
            41.8625 to -87.7260,  // NW
            41.8625 to -87.7200,  // NE
            41.8595 to -87.7200,  // SE
            41.8595 to -87.7260,  // SW
        ),
        fundingGoalCents = 300000,
        fundingCurrentCents = 90000,
        status = BlockStatus.AVAILABLE,
    ),

    // NL-009: California & 21st — work in progress
    MapBlock(
        id = 9,
        code = "NL-009",
        name = "California & 21st",
        polygon = listOf(
            41.8540 to -87.6970,  // NW
            41.8540 to -87.6920,  // NE
            41.8510 to -87.6920,  // SE
            41.8510 to -87.6970,  // SW
        ),
        fundingGoalCents = 400000,
        fundingCurrentCents = 320000,
        status = BlockStatus.IN_PROGRESS,
    ),

    // NL-010: Hamlin & 19th
    MapBlock(
        id = 10,
        code = "NL-010",
        name = "Hamlin & 19th",
        polygon = listOf(
            41.8570 to -87.7200,  // NW
            41.8570 to -87.7140,  // NE
            41.8545 to -87.7140,  // SE
            41.8545 to -87.7200,  // SW
        ),
        fundingGoalCents = 350000,
        fundingCurrentCents = 100000,
        status = BlockStatus.AVAILABLE,
    ),
)
