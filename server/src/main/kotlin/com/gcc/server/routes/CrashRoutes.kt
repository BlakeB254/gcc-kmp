package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.services.CrashReportService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.CrashReportRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.crashRoutes() {
    val crashReportService = application.koinGet<CrashReportService>()
    post("/api/crashes") {
        val request = call.receive<CrashReportRequest>()
        crashReportService.record(request)
        call.respond(HttpStatusCode.OK, ApiResponse(data = Unit, success = true, message = null))
    }
}
