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
 */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val r2: R2Config?,
    val indexSecret: String?,
    val indexIntervalMinutes: Long,
) {
    companion object {
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
            )
        }
    }
}
