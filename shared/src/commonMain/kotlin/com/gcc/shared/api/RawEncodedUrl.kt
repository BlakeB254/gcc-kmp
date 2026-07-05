package com.gcc.shared.api

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.parseQueryString

/**
 * Populate a [URLBuilder] from an ALREADY-ENCODED URL string without any
 * decode/re-encode round trip.
 *
 * Ktor's normal `takeFrom(String)` decodes path segments and query values,
 * then re-encodes them on build with its own rules — which changes
 * percent-encoding (`%2F` ↔ `/`, `%3A` ↔ `:`, …). For AWS-SigV4 presigned
 * URLs (R2 uploads) the encoding IS part of the signature, so any
 * normalization makes the storage host reject the request. This helper
 * copies the scheme/host/port and passes the path + query through in their
 * raw encoded form.
 */
fun URLBuilder.takeFromRawEncoded(urlString: String) {
    val schemeEnd = urlString.indexOf("://")
    require(schemeEnd > 0) { "absolute URL required" }
    protocol = URLProtocol.createOrDefault(urlString.substring(0, schemeEnd))

    val afterScheme = urlString.substring(schemeEnd + 3)
    val pathStart = afterScheme.indexOf('/')
    val authority = if (pathStart >= 0) afterScheme.substring(0, pathStart) else afterScheme
    val portSplit = authority.lastIndexOf(':')
    if (portSplit >= 0 && authority.substring(portSplit + 1).toIntOrNull() != null) {
        host = authority.substring(0, portSplit)
        port = authority.substring(portSplit + 1).toInt()
    } else {
        host = authority
        port = protocol.defaultPort
    }

    if (pathStart < 0) return
    val pathAndQuery = afterScheme.substring(pathStart)
    val queryStart = pathAndQuery.indexOf('?')
    val rawPath = if (queryStart >= 0) pathAndQuery.substring(0, queryStart) else pathAndQuery
    encodedPathSegments = rawPath.removePrefix("/").split("/")
    if (queryStart >= 0) {
        // decode = false: values are appended exactly as they appear and the
        // builder emits them verbatim.
        encodedParameters.appendAll(
            parseQueryString(pathAndQuery.substring(queryStart + 1), decode = false),
        )
    }
}
