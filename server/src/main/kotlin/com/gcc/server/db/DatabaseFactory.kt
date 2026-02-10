package com.gcc.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

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

    /** Execute a database query on the IO dispatcher within a transaction. */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    /** Convert postgres:// URL to jdbc:postgresql:// format, preserving query params (e.g. sslmode). */
    private fun convertToJdbcUrl(url: String): String {
        return url
            .replace("postgres://", "jdbc:postgresql://")
            .replace("postgresql://", "jdbc:postgresql://")
    }
}
