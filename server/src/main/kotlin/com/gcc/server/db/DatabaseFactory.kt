package com.gcc.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    fun init() {
        val databaseUrl = System.getenv("DATABASE_URL")
            ?: throw IllegalStateException("DATABASE_URL environment variable is required")

        val config = HikariConfig().apply {
            jdbcUrl = convertToJdbcUrl(databaseUrl)
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        Database.connect(HikariDataSource(config))
    }

    /** Convert postgres:// URL to jdbc:postgresql:// format */
    private fun convertToJdbcUrl(url: String): String {
        return url
            .replace("postgres://", "jdbc:postgresql://")
            .replace("postgresql://", "jdbc:postgresql://")
    }
}
