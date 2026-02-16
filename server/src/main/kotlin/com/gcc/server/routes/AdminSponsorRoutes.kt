package com.gcc.server.routes

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Sponsors
import com.gcc.server.plugins.requireAdmin
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.Sponsor
import com.gcc.shared.model.SponsorBusiness
import com.gcc.shared.model.SponsorPreferences
import com.gcc.shared.model.SponsorStats
import com.gcc.shared.model.SponsorStripe
import com.gcc.shared.model.SponsorType
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*

fun Route.adminSponsorRoutes() {
    route("/api/admin/sponsors") {
        get {
            call.requireAdmin()
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0
            val sponsors = dbQuery {
                Sponsors.selectAll()
                    .orderBy(Sponsors.createdAt, SortOrder.DESC)
                    .limit(limit).offset(offset)
                    .map { it.toSponsor() }
            }
            call.respond(HttpStatusCode.OK, ApiResponse(data = sponsors))
        }

        get("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Sponsor ID required")
            val sponsor = dbQuery {
                Sponsors.selectAll().where { Sponsors.id eq id }
                    .firstOrNull()?.toSponsor()
            }
            if (sponsor != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = sponsor))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Sponsor not found", 404))
            }
        }

        put("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Sponsor ID required")
            val request = call.receive<UpdateSponsorRequest>()
            val now = Clock.System.now()
            val updated = dbQuery {
                Sponsors.update({ Sponsors.id eq id }) {
                    request.name?.let { name -> it[Sponsors.name] = name }
                    request.phone?.let { phone -> it[Sponsors.phone] = phone }
                    request.type?.let { type -> it[Sponsors.type] = type }
                    it[updatedAt] = now
                }
                Sponsors.selectAll().where { Sponsors.id eq id }
                    .firstOrNull()?.toSponsor()
            }
            if (updated != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = updated))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Sponsor not found", 404))
            }
        }
    }
}

@Serializable
data class UpdateSponsorRequest(
    val name: String? = null,
    val phone: String? = null,
    val type: String? = null,
)

private fun ResultRow.toSponsor(): Sponsor = Sponsor(
    id = this[Sponsors.id],
    name = this[Sponsors.name],
    email = this[Sponsors.email],
    phone = this[Sponsors.phone],
    emailVerified = this[Sponsors.emailVerified],
    type = com.gcc.server.services.enumValueOfOrDefault(this[Sponsors.type]),
    business = SponsorBusiness(
        name = this[Sponsors.businessName],
        website = this[Sponsors.businessWebsite],
    ),
    preferences = SponsorPreferences(
        frequency = com.gcc.server.services.enumValueOfOrDefault(this[Sponsors.preferencesFrequency]),
        isAnonymous = this[Sponsors.preferencesIsAnonymous],
        wantsUpdates = this[Sponsors.preferencesWantsUpdates],
    ),
    stats = SponsorStats(
        totalDonated = this[Sponsors.statsTotalContributed]?.toIntOrNull() ?: 0,
        blocksSponsored = this[Sponsors.statsBlocksFunded]?.toIntOrNull() ?: 0,
        lastDonationAt = this[Sponsors.statsLastContributionAt],
    ),
    stripe = SponsorStripe(
        customerId = this[Sponsors.stripeCustomerId],
        subscriptionId = this[Sponsors.stripeSubscriptionId],
        defaultPaymentMethod = this[Sponsors.stripeDefaultPaymentMethod],
    ),
    createdAt = this[Sponsors.createdAt],
    updatedAt = this[Sponsors.updatedAt],
)
