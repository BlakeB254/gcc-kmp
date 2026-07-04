package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.CrashReports
import com.gcc.shared.model.CrashReportRequest
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert

open class CrashReportService {
    open suspend fun record(request: CrashReportRequest): Unit = dbQuery {
        CrashReports.insert {
            it[deviceId] = request.deviceId
            it[platform] = request.platform
            it[appVersion] = request.appVersion
            it[exceptionMessage] = request.exceptionMessage
            it[stackTrace] = request.stackTrace
            it[occurredAt] = request.occurredAt
            it[createdAt] = Clock.System.now()
        }
    }
}
