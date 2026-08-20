package com.gcc.backend

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI

/** Cloudflare R2 credentials, read from the same env var names the Next.js app uses. */
data class R2Config(
    val accountId: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
) {
    companion object {
        fun fromEnvOrNull(): R2Config? {
            val accountId = System.getenv("R2_ACCOUNT_ID")
            val accessKeyId = System.getenv("R2_ACCESS_KEY_ID")
            val secretAccessKey = System.getenv("R2_SECRET_ACCESS_KEY")
            val bucket = System.getenv("R2_BUCKET_NAME")
            return if (!accountId.isNullOrBlank() && !accessKeyId.isNullOrBlank() &&
                !secretAccessKey.isNullOrBlank() && !bucket.isNullOrBlank()
            ) R2Config(accountId, accessKeyId, secretAccessKey, bucket) else null
        }
    }
}

/**
 * Thin R2 wrapper over the AWS S3 SDK v2 — R2 is S3-compatible, region "auto",
 * path-style addressing. The SDK handles SigV4 correctly (worth noting: the
 * mobile client's presigned-PUT path had two SigV4 traps; using the SDK here
 * side-steps both). Read-only for now: list object keys+sizes, fetch a manifest.
 */
class R2Storage(private val config: R2Config) : AutoCloseable {
    private val client: S3Client = S3Client.builder()
        .region(Region.of("auto"))
        .endpointOverride(URI("https://${config.accountId}.r2.cloudflarestorage.com"))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)
            )
        )
        .httpClient(ApacheHttpClient.create())
        .forcePathStyle(true)
        .build()

    /** Size + last-modified for one object in a listing. */
    data class ObjectInfo(val size: Long, val lastModifiedEpochMs: Long)

    /** Every object under [prefix], as key -> size in bytes. Paginates fully. */
    fun listAll(prefix: String): Map<String, Long> =
        listAllWithInfo(prefix).mapValues { it.value.size }

    /** Every object under [prefix], as key -> size + last-modified. Paginates fully. */
    fun listAllWithInfo(prefix: String): Map<String, ObjectInfo> {
        val out = LinkedHashMap<String, ObjectInfo>()
        var token: String? = null
        do {
            val currentToken = token
            val request = ListObjectsV2Request.builder()
                .bucket(config.bucket)
                .prefix(prefix)
                .let { if (currentToken != null) it.continuationToken(currentToken) else it }
                .build()
            val response = client.listObjectsV2(request)
            for (obj in response.contents()) {
                out[obj.key()] = ObjectInfo(obj.size(), obj.lastModified()?.toEpochMilli() ?: 0L)
            }
            token = if (response.isTruncated == true) response.nextContinuationToken() else null
        } while (token != null)
        return out
    }

    /** Delete a single object. Callers decide policy; this is just the verb. */
    fun deleteObject(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
    }

    /** Fetch a (small, text) object — used to read manifest JSON. */
    fun getText(key: String): String =
        client.getObject(GetObjectRequest.builder().bucket(config.bucket).key(key).build())
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** Fetch a small binary object — used for the gzipped flight-recorder
     *  event stream (`events.jsonl.gz`, <200KB by contract). */
    fun getBytes(key: String): ByteArray =
        client.getObject(GetObjectRequest.builder().bucket(config.bucket).key(key).build())
            .use { it.readBytes() }

    override fun close() = client.close()
}
