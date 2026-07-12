package com.gcc.app.platform

import java.io.InputStream
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal localhost stand-in for the scan-upload endpoint pair, shared by the
 * uploader and orphan-recovery suites: POST /api/captures/upload-url answers
 * with a presigned-looking uploadUrl pointing back at this server (key built
 * with the real route's two layouts — legacy deviceId/capturedAt and
 * session-artifact sessionId/artifactPath); PUT /put/... accepts artifact
 * bytes. Serial accept loop — the uploader's requests are sequential.
 */
internal class TestScanServer : AutoCloseable {
    class Recorded(val method: String, val path: String, val body: ByteArray)

    private val server = ServerSocket(0)
    val base get() = "http://127.0.0.1:${server.localPort}"
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    /** Return the HTTP status for a PUT of [path] — tests inject failures here. */
    @Volatile
    var putStatus: (String) -> Int = { 200 }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                socket.use { s ->
                    try {
                        val (requestLine, _, body) = readRequest(s.getInputStream())
                        val (method, path) = requestLine.split(" ").let { it[0] to it[1] }
                        requests.add(Recorded(method, path, body))
                        s.getOutputStream().write(respond(method, path, body))
                        s.getOutputStream().flush()
                    } catch (e: Exception) {
                        // torn connection — let the client see the failure
                    }
                }
            }
        }
    }

    private fun respond(method: String, path: String, body: ByteArray): ByteArray {
        return when {
            method == "POST" && path == "/api/captures/upload-url" -> {
                val req = Json.parseToJsonElement(body.decodeToString()).jsonObject
                val cell = req["locationCellId"]!!.jsonPrimitive.content
                // GccJson encodes defaults, so absent-optional fields arrive
                // as JSON nulls — contentOrNull, not content ("null").
                val sessionId = req["sessionId"]?.jsonPrimitive?.contentOrNull
                val artifactPath = req["artifactPath"]?.jsonPrimitive?.contentOrNull
                val key = if (!sessionId.isNullOrEmpty() && !artifactPath.isNullOrEmpty()) {
                    "raw/$cell/$sessionId/$artifactPath"
                } else {
                    val deviceId = req["deviceId"]!!.jsonPrimitive.content
                    val capturedAt = req["capturedAt"]!!.jsonPrimitive.content
                    val ext = req["ext"]!!.jsonPrimitive.content
                    "raw/$cell/$deviceId/$capturedAt.$ext"
                }
                val payload =
                    """{"data":{"uploadUrl":"$base/put/$key","r2Key":"$key","expiresInSeconds":300},"success":true,"message":null}"""
                http(200, payload.encodeToByteArray(), "application/json")
            }
            method == "PUT" -> http(putStatus(path), ByteArray(0), "text/plain")
            else -> http(404, ByteArray(0), "text/plain")
        }
    }

    private fun http(status: Int, body: ByteArray, contentType: String): ByteArray {
        val reason = if (status == 200) "OK" else "ERR"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        return head.encodeToByteArray() + body
    }

    private fun readRequest(input: InputStream): Triple<String, Map<String, String>, ByteArray> {
        val headerBytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBytes.add(b.toByte())
            val n = headerBytes.size
            if (n >= 4 &&
                headerBytes[n - 4] == '\r'.code.toByte() && headerBytes[n - 3] == '\n'.code.toByte() &&
                headerBytes[n - 2] == '\r'.code.toByte() && headerBytes[n - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        val headerText = headerBytes.toByteArray().decodeToString()
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        val headers = lines.drop(1).associate {
            val idx = it.indexOf(':')
            it.substring(0, idx).lowercase() to it.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return Triple(lines.first(), headers, body)
    }

    override fun close() = server.close()
}
