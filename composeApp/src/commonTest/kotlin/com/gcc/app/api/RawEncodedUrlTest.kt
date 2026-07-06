package com.gcc.app.api

import com.gcc.shared.api.takeFromRawEncoded

import io.ktor.http.URLBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class RawEncodedUrlTest {

    /** A presigned URL must survive the builder byte-for-byte — the
     *  percent-encoding is part of the AWS SigV4 signature. */
    @Test
    fun presignedUrlRoundTripsExactly() {
        val presigned = "https://gcc-scan-storage.f03e6200e05aab1834619df30a675c62.r2.cloudflarestorage.com" +
            "/raw/418588%3A-877243/device-1/env-1783257334722-lybsqg-manifest.json" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Content-Sha256=UNSIGNED-PAYLOAD" +
            "&X-Amz-Credential=68565e887aa6340bf18c612b4255267b%2F20260705%2Fauto%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20260705T131600Z" +
            "&X-Amz-Expires=300" +
            "&X-Amz-Signature=abc123def456" +
            "&X-Amz-SignedHeaders=host"
        val built = URLBuilder().apply { takeFromRawEncoded(presigned) }.buildString()
        assertEquals(presigned, built)
    }

    /** AWS SDK ≥3.729 presigns with flexible-checksum query params — the
     *  base64 `%3D%3D` padding in x-amz-checksum-crc32 and the lowercase
     *  param names must also survive byte-for-byte. */
    @Test
    fun presignedUrlWithChecksumParamsRoundTripsExactly() {
        val presigned = "https://gcc-scan-storage.f03e6200e05aab1834619df30a675c62.r2.cloudflarestorage.com" +
            "/raw/465773%3A-718118/debugsession0706/env-debug-0706-manifest.json" +
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
        val built = URLBuilder().apply { takeFromRawEncoded(presigned) }.buildString()
        assertEquals(presigned, built)
    }

    @Test
    fun plainUrlWithPortRoundTrips() {
        val url = "http://localhost:8787/bucket/key.json?token=a%2Fb"
        val built = URLBuilder().apply { takeFromRawEncoded(url) }.buildString()
        assertEquals(url, built)
    }

    @Test
    fun pathOnlyUrlRoundTrips() {
        val url = "https://example.com/some%20path/file.usdz"
        val built = URLBuilder().apply { takeFromRawEncoded(url) }.buildString()
        assertEquals(url, built)
    }
}
