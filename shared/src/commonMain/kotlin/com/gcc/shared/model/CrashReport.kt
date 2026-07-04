package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CrashReportRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String? = null,
    val exceptionMessage: String,
    val stackTrace: String,
    val occurredAt: Instant,
)
