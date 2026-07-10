package com.gcc.backend

import com.gcc.shared.geo.gridCell10m
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * Mirrors GCC-relevant Chicago 311 service requests (Data Portal dataset
 * `v6vf-nfxy`, SODA API) into Neon's `civic_reports`, keyed by the city's own
 * `sr_number` (docs/superpowers/specs/2026-07-10-chicago-311-civic-loop-design.md §4).
 *
 * Watermarked on `last_modified_date` (persisted as MAX(last_modified_date)
 * in the table itself — no separate state row), so status transitions
 * (Open → Completed) update in place on later sweeps. NO status filter in the
 * query for exactly that reason; the app's nearby-prompt query filters
 * status = 'Open' at read time. Cell ids are computed with the SAME shared
 * `gridCell10m` the mobile client uses — the byte-equality contract that
 * makes a 311 report addressable by the scan world model.
 */
class CivicReportSync(
    private val dataSource: DataSource,
    private val srTypes: List<String>,
    private val backfillDays: Long,
    private val appToken: String? = null,
    private val baseUrl: String = "https://data.cityofchicago.org/resource/v6vf-nfxy.json",
) {
    private val log = LoggerFactory.getLogger("civic-sync")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    @Serializable
    data class Result(val fetched: Int, val upserted: Int, val pages: Int, val watermark: String, val durationMs: Long)

    fun runOnce(): Result {
        val start = System.currentTimeMillis()
        var watermark = currentWatermark()
        var fetched = 0
        var upserted = 0
        var pages = 0

        while (pages < MAX_PAGES_PER_SWEEP) {
            val rows = fetchPage(watermark)
            if (rows.isEmpty()) break
            pages++
            fetched += rows.size
            upserted += upsert(rows)
            val maxModified = rows.mapNotNull { it.lastModifiedDate }.maxOrNull() ?: break
            if (maxModified <= watermark) break // no forward progress — stop rather than spin
            watermark = maxModified
            if (rows.size < PAGE_SIZE) break
        }

        return Result(fetched, upserted, pages, watermark.toString(), System.currentTimeMillis() - start)
    }

    private data class SodaRow(
        val srNumber: String,
        val srType: String,
        val status: String,
        val ownerDepartment: String?,
        val createdDate: Instant?,
        val lastModifiedDate: Instant?,
        val closedDate: Instant?,
        val streetAddress: String?,
        val communityArea: Int?,
        val lat: Double?,
        val lng: Double?,
    )

    /** Resume point: the newest last_modified_date we've stored, else now - backfillDays. */
    private fun currentWatermark(): Instant {
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT max(last_modified_date) FROM civic_reports").use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getTimestamp(1)?.let { return it.toInstant() }
                }
            }
        }
        return Instant.now().minus(backfillDays, ChronoUnit.DAYS)
    }

    private fun fetchPage(since: Instant): List<SodaRow> {
        val typeList = srTypes.joinToString(",") { "'${it.replace("'", "''")}'" }
        // SODA floating timestamps are quoted ISO-8601 without zone; the portal
        // publishes in local (Chicago) floating time — we pass the watermark
        // back exactly as stored, so comparisons stay internally consistent.
        val sinceLiteral = since.truncatedTo(ChronoUnit.SECONDS).toString().removeSuffix("Z")
        val where = "sr_type in($typeList) AND last_modified_date > '$sinceLiteral'"
        val query = "?\$where=" + URLEncoder.encode(where, Charsets.UTF_8) +
            "&\$order=" + URLEncoder.encode("last_modified_date", Charsets.UTF_8) +
            "&\$limit=$PAGE_SIZE"
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + query))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
        appToken?.let { builder.header("X-App-Token", it) }

        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("SODA fetch failed: HTTP {} {}", response.statusCode(), response.body().take(200))
            return emptyList()
        }
        return json.parseToJsonElement(response.body()).jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val srNumber = obj.str("sr_number") ?: return@mapNotNull null
            SodaRow(
                srNumber = srNumber,
                srType = obj.str("sr_type") ?: return@mapNotNull null,
                status = obj.str("status") ?: "Unknown",
                ownerDepartment = obj.str("owner_department"),
                createdDate = obj.instant("created_date"),
                lastModifiedDate = obj.instant("last_modified_date"),
                closedDate = obj.instant("closed_date"),
                streetAddress = obj.str("street_address"),
                communityArea = obj.str("community_area")?.toIntOrNull(),
                lat = obj.str("latitude")?.toDoubleOrNull(),
                lng = obj.str("longitude")?.toDoubleOrNull(),
            )
        }
    }

    private fun upsert(rows: List<SodaRow>): Int {
        var changed = 0
        dataSource.connection.use { c ->
            c.prepareStatement(UPSERT).use { ps ->
                for (row in rows) {
                    var i = 1
                    ps.setString(i++, row.srNumber)
                    ps.setString(i++, row.srType.take(100))
                    ps.setString(i++, row.status.take(50))
                    ps.setNullableString(i++, row.ownerDepartment?.take(100))
                    ps.setNullableInstant(i++, row.createdDate)
                    ps.setNullableInstant(i++, row.lastModifiedDate)
                    ps.setNullableInstant(i++, row.closedDate)
                    ps.setNullableString(i++, row.streetAddress?.take(200))
                    if (row.communityArea == null) ps.setNull(i++, Types.INTEGER) else ps.setInt(i++, row.communityArea)
                    if (row.lat == null) ps.setNull(i++, Types.DOUBLE) else ps.setDouble(i++, row.lat)
                    if (row.lng == null) ps.setNull(i++, Types.DOUBLE) else ps.setDouble(i++, row.lng)
                    val cell = if (row.lat != null && row.lng != null) gridCell10m(row.lat, row.lng) else null
                    ps.setNullableString(i++, cell)
                    ps.addBatch()
                }
                changed = ps.executeBatch().sum()
            }
        }
        return changed
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.instant(key: String): Instant? = str(key)?.let {
        // Portal floating timestamps have no zone suffix; Instant.parse needs one.
        runCatching { Instant.parse(if (it.endsWith("Z")) it else it + "Z") }.getOrNull()
    }

    private fun java.sql.PreparedStatement.setNullableString(idx: Int, v: String?) =
        if (v == null) setNull(idx, Types.VARCHAR) else setString(idx, v)

    private fun java.sql.PreparedStatement.setNullableInstant(idx: Int, v: Instant?) =
        if (v == null) setNull(idx, Types.TIMESTAMP) else setTimestamp(idx, Timestamp.from(v))

    companion object {
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES_PER_SWEEP = 50

        private val UPSERT = """
            INSERT INTO civic_reports
              (sr_number, sr_type, status, owner_department, created_date, last_modified_date,
               closed_date, street_address, community_area, lat, lng, location_cell_id, synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (sr_number) DO UPDATE SET
               sr_type = EXCLUDED.sr_type,
               status = EXCLUDED.status,
               owner_department = EXCLUDED.owner_department,
               created_date = EXCLUDED.created_date,
               last_modified_date = EXCLUDED.last_modified_date,
               closed_date = EXCLUDED.closed_date,
               street_address = EXCLUDED.street_address,
               community_area = EXCLUDED.community_area,
               lat = EXCLUDED.lat,
               lng = EXCLUDED.lng,
               location_cell_id = EXCLUDED.location_cell_id,
               synced_at = now()
        """.trimIndent()
    }
}
