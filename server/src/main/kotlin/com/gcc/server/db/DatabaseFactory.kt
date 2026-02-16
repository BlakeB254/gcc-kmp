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

        val uri = java.net.URI(databaseUrl.replaceFirst("postgres://", "postgresql://"))
        val parts = uri.userInfo?.split(":", limit = 2)
        val dbUser = parts?.getOrNull(0)
        val dbPass = parts?.getOrNull(1)
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 5432
        val database = uri.path.removePrefix("/")
        val baseQuery = uri.query ?: ""
        val separator = if (baseQuery.isEmpty()) "?" else "?$baseQuery&"
        val query = "${separator}stringtype=unspecified"

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$database$query"
            driverClassName = "org.postgresql.Driver"
            username = dbUser
            password = dbPass
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
}
