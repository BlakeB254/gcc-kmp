package com.gcc.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.URLDecoder
import javax.sql.DataSource

/**
 * Builds a Hikari pool from a `postgres://user:pass@host/db?params` style URL
 * (the shape Neon and most platforms expose) — splitting the userinfo out of
 * the URL and forcing `sslmode=require`, which Neon needs. Small bounded pool:
 * this is a single always-on JVM doing periodic sweeps, not a request fan-out.
 */
fun createDataSource(databaseUrl: String): DataSource {
    val uri = URI(databaseUrl)
    val userInfo = uri.userInfo?.split(":", limit = 2)
    val user = userInfo?.getOrNull(0)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
    val pass = userInfo?.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
    val port = if (uri.port == -1) 5432 else uri.port

    val params = buildList {
        uri.query?.let { add(it) }
        if (uri.query?.contains("sslmode=") != true) add("sslmode=require")
    }
    val jdbcUrl = buildString {
        append("jdbc:postgresql://").append(uri.host).append(':').append(port).append(uri.path)
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }

    val config = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        if (user != null) username = user
        if (pass != null) password = pass
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 5
        minimumIdle = 1
        poolName = "gcc-backend"
    }
    return HikariDataSource(config)
}
