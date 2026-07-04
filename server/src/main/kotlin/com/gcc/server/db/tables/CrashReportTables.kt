package com.gcc.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CrashReports : Table("crash_reports") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 255)
    val platform = varchar("platform", 32)
    val appVersion = varchar("app_version", 64).nullable()
    val exceptionMessage = text("exception_message")
    val stackTrace = text("stack_trace")
    val occurredAt = timestamp("occurred_at")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
