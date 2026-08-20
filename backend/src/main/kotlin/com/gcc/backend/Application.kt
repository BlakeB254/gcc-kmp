package com.gcc.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        module(config)
    }.start(wait = true)
}

@Serializable
private data class ErrorResponse(val error: String)

fun Application.module(config: Config) {
    val log = LoggerFactory.getLogger("gcc-backend")

    install(ContentNegotiation) { json(Json { prettyPrint = true; ignoreUnknownKeys = true }) }
    install(CallLogging)

    val dataSource = createDataSource(config.databaseUrl)
    val r2 = config.r2?.let { R2Storage(it) }
    val indexer = r2?.let { ScanIndexer(it, ScanIndexRepository(dataSource)) }

    if (r2 == null) log.warn("R2 not configured — scan indexer is idle (set R2_* env vars to enable).")

    // Background sweep. The DGX host keeps this process alive continuously, so a
    // simple coroutine loop is enough — no external scheduler needed.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    if (indexer != null && config.indexIntervalMinutes > 0) {
        scope.launch {
            while (isActive) {
                try {
                    val result = withContext(Dispatchers.IO) { indexer.runOnce() }
                    log.info(
                        "scan-index sweep: {} sessions, {} chunks, {} reconstructions, {} failed ({}ms)",
                        result.sessionsIndexed, result.chunksIndexed, result.reconstructionsIndexed,
                        result.manifestsFailed, result.durationMs,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("scan-index sweep failed", e)
                }
                delay(config.indexIntervalMinutes.minutes)
            }
        }
    }

    // Chicago 311 civic mirror (2026-07-10 civic-loop spec §4) — same
    // keep-alive-coroutine pattern as the scan indexer above.
    val civicSync = CivicReportSync(dataSource, config.civicSrTypes, config.civicBackfillDays, config.socrataAppToken)
    if (config.civicSyncIntervalMinutes > 0) {
        scope.launch {
            while (isActive) {
                try {
                    val result = withContext(Dispatchers.IO) { civicSync.runOnce() }
                    log.info(
                        "civic sweep: {} fetched, {} upserted, {} pages, watermark {} ({}ms)",
                        result.fetched, result.upserted, result.pages, result.watermark, result.durationMs,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("civic sweep failed", e)
                }
                delay(config.civicSyncIntervalMinutes.minutes)
            }
        }
    }

    // Verification-photo retention sweep (PII hygiene for government ID
    // uploads) — same keep-alive-coroutine pattern. Only constructed when the
    // operator opts in with VERIFICATION_RETENTION_DAYS > 0, and dry-run even
    // then unless VERIFICATION_RETENTION_DRY_RUN=false.
    val retention = if (r2 != null && config.verificationRetentionDays > 0) {
        VerificationPhotoRetention(r2, dataSource, config.verificationRetentionDays, config.verificationRetentionDryRun)
    } else null
    if (retention != null) {
        log.info(
            "verification retention enabled: {} days, dryRun={}, every {}m",
            config.verificationRetentionDays, config.verificationRetentionDryRun,
            config.verificationRetentionIntervalMinutes,
        )
    }
    if (retention != null && config.verificationRetentionIntervalMinutes > 0) {
        scope.launch {
            while (isActive) {
                try {
                    val result = withContext(Dispatchers.IO) { retention.runOnce() }
                    log.info(
                        "verification-retention sweep: {} scanned, {} expired ({} referenced, {} protected by review flow), {} deleted, {} tombstoned, dryRun={} ({}ms)",
                        result.scanned, result.expired, result.expiredReferenced, result.skippedPendingReview,
                        result.deleted, result.tombstoned, result.dryRun, result.durationMs,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("verification-retention sweep failed", e)
                }
                delay(config.verificationRetentionIntervalMinutes.minutes)
            }
        }
    }

    monitor.subscribe(ApplicationStopping) {
        scope.cancel()
        r2?.close()
        (dataSource as? AutoCloseable)?.close()
    }

    routing {
        // Cheap liveness check — no DB/R2 touch, so a transient datastore blip
        // never fails the host's health probe.
        get("/healthz") { call.respondText("ok") }
        get("/") { call.respondText("gcc-backend: scan control-plane") }

        // Manual trigger for an immediate sweep (bearer-guarded, fail-closed).
        post("/admin/index-scans") {
            if (!authorized(call.request.header(HttpHeaders.Authorization), config.indexSecret)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            if (indexer == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("R2 not configured"))
                return@post
            }
            val result = withContext(Dispatchers.IO) { indexer.runOnce() }
            call.respond(result)
        }

        // Manual trigger for the Chicago-311 mirror sweep (same guard).
        post("/admin/sync-civic") {
            if (!authorized(call.request.header(HttpHeaders.Authorization), config.indexSecret)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            val result = withContext(Dispatchers.IO) { civicSync.runOnce() }
            call.respond(result)
        }

        // Manual trigger for a verification-photo retention sweep (same guard).
        // Honors the configured dry-run mode — deletion requires
        // VERIFICATION_RETENTION_DRY_RUN=false in the environment.
        post("/admin/purge-verification") {
            if (!authorized(call.request.header(HttpHeaders.Authorization), config.indexSecret)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            if (retention == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("retention disabled (set VERIFICATION_RETENTION_DAYS > 0)"),
                )
                return@post
            }
            val result = withContext(Dispatchers.IO) { retention.runOnce() }
            call.respond(result)
        }
    }
}

private fun authorized(authHeader: String?, secret: String?): Boolean {
    if (secret.isNullOrBlank()) return false // fail closed if no secret set
    return authHeader == "Bearer $secret"
}
