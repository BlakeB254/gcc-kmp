package com.gcc.app.api

import com.gcc.shared.api.GccApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Wire-level checks for [GccApiClient.uploadToPresignedUrl]: a presigned R2
 * PUT is self-authenticating (AWS SigV4 lives in the query string), so the
 * request must go out with the exact presigned request-target and WITHOUT the
 * app's Bearer token — S3/R2 reject any request carrying both query-string
 * auth and an Authorization header with 400 InvalidRequest ("Missing
 * x-amz-content-sha256"), which is exactly what every on-device scan upload
 * hit while curl verification (no bearer header) kept passing.
 */
class PresignedUploadWireTest {

    private fun captureRequest(
        put: suspend (baseUrl: String) -> Unit,
    ): String = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val captured = async(Dispatchers.IO) {
            server.accept().use { socket ->
                val buf = ByteArray(65536)
                val n = socket.getInputStream().read(buf)
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                )
                socket.getOutputStream().flush()
                buf.copyOf(if (n > 0) n else 0).decodeToString()
            }
        }
        put("http://127.0.0.1:$port")
        withContext(Dispatchers.IO) { captured.await() }.also { server.close() }
    }

    private val presignedPathAndQuery =
        "/raw/465773%3A-718118/device-1/env-1783347520720-fuvaue-manifest.json" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Content-Sha256=UNSIGNED-PAYLOAD" +
            "&X-Amz-Credential=68565e887aa6340bf18c612b4255267b%2F20260706%2Fauto%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20260706T152802Z" +
            "&X-Amz-Expires=300" +
            "&X-Amz-Signature=36ec5294e025561d140e27b94e5d176368fa638c381eb979a1eb6f35cc3e63f1" +
            "&X-Amz-SignedHeaders=host" +
            "&x-amz-checksum-crc32=AAAAAA%3D%3D" +
            "&x-amz-sdk-checksum-algorithm=CRC32" +
            "&x-id=PutObject"

    @Test
    fun presignedPutSendsExactRequestTargetAndNoAuthorizationHeader() {
        // Token provider present — like the real logged-in app client.
        val client = GccApiClient(
            baseUrl = "http://unused.example",
            tokenProvider = { "test-jwt-token" },
        )
        val request = captureRequest { base ->
            client.uploadToPresignedUrl(
                "$base$presignedPathAndQuery",
                "{\"debug\":\"wire\"}".encodeToByteArray(),
                "application/json",
            )
        }
        val lines = request.split("\r\n")
        assertEquals("PUT $presignedPathAndQuery HTTP/1.1", lines.first())
        assertFalse(
            lines.any { it.startsWith("Authorization:", ignoreCase = true) },
            "presigned PUT must not carry the app Bearer token — R2 rejects " +
                "mixed auth with 400. Got:\n$request",
        )
    }
}
