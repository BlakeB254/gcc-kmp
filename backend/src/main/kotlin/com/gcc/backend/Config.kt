package com.gcc.backend

/**
 * Runtime configuration for the GCC backend (scan control-plane), read entirely
 * from environment variables so the same image runs unchanged on the DGX, a
 * laptop, or any container host.
 *
 *  - PORT                     HTTP port (host injects it; default 8080)
 *  - DATABASE_URL             Neon Postgres URL (postgres:// or postgresql://)
 *  - R2_ACCOUNT_ID            Cloudflare R2 account id
 *  - R2_ACCESS_KEY_ID         R2 access key
 *  - R2_SECRET_ACCESS_KEY     R2 secret
 *  - R2_BUCKET_NAME           R2 bucket (gcc-scan-storage)
 *  - INDEX_SECRET             bearer token guarding POST /admin/index-scans
 *  - INDEX_INTERVAL_MINUTES   background sweep cadence (default 30; 0 disables)
 *  - CIVIC_SYNC_INTERVAL_MINUTES  Chicago 311 mirror cadence (default 360; 0 disables)
 *  - CIVIC_SR_TYPES           comma-separated SODA sr_type filter (default: the
 *                             seven cleanup types in the 2026-07-10 civic-loop spec)
 *  - CIVIC_BACKFILL_DAYS      initial watermark lookback (default 90)
 *  - SOCRATA_APP_TOKEN        optional; lifts the shared SODA throttle
 *  - VERIFICATION_RETENTION_DAYS    purge verification/ ID photos older than
 *                                   this many days (default 0 = job disabled)
 *  - VERIFICATION_RETENTION_DRY_RUN "false" actually deletes; anything else
 *                                   (default true) only logs what it would do
 *  - VERIFICATION_RETENTION_INTERVAL_MINUTES  sweep cadence (default 720)
 */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val r2: R2Config?,
    val indexSecret: String?,
    val indexIntervalMinutes: Long,
    val civicSyncIntervalMinutes: Long,
    val civicSrTypes: List<String>,
    val civicBackfillDays: Long,
    val socrataAppToken: String?,
    val verificationRetentionDays: Long,
    val verificationRetentionDryRun: Boolean,
    val verificationRetentionIntervalMinutes: Long,
) {
    companion object {
        val DEFAULT_CIVIC_SR_TYPES = listOf(
            "Graffiti Removal Request",
            "Fly Dumping Complaint",
            "Sanitation Code Violation",
            "Weed Removal Request",
            "Clean Vacant Lot Request",
            "Street Cleaning Request",
            "Tree Debris Clean-Up Request",
        )

        fun fromEnv(): Config {
            val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
            val databaseUrl = System.getenv("DATABASE_URL")?.takeIf { it.isNotBlank() }
                ?: error("DATABASE_URL is required")
            return Config(
                port = port,
                databaseUrl = databaseUrl,
                r2 = R2Config.fromEnvOrNull(),
                indexSecret = System.getenv("INDEX_SECRET")?.takeIf { it.isNotBlank() },
                indexIntervalMinutes = System.getenv("INDEX_INTERVAL_MINUTES")?.toLongOrNull() ?: 30L,
                civicSyncIntervalMinutes = System.getenv("CIVIC_SYNC_INTERVAL_MINUTES")?.toLongOrNull() ?: 360L,
                civicSrTypes = System.getenv("CIVIC_SR_TYPES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: DEFAULT_CIVIC_SR_TYPES,
                civicBackfillDays = System.getenv("CIVIC_BACKFILL_DAYS")?.toLongOrNull() ?: 90L,
                socrataAppToken = System.getenv("SOCRATA_APP_TOKEN")?.takeIf { it.isNotBlank() },
                verificationRetentionDays = System.getenv("VERIFICATION_RETENTION_DAYS")?.toLongOrNull() ?: 0L,
                verificationRetentionDryRun = System.getenv("VERIFICATION_RETENTION_DRY_RUN")?.trim()?.lowercase() != "false",
                verificationRetentionIntervalMinutes =
                    System.getenv("VERIFICATION_RETENTION_INTERVAL_MINUTES")?.toLongOrNull() ?: 720L,
            )
        }
    }
}
