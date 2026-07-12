package com.gcc.app.platform

/**
 * Gzip-compress [data] (RFC 1952 framing — standard `gunzip`/`GZIPInputStream`
 * readable, NOT bare deflate). Used by the scan-session flight recorder to
 * upload the event stream as `events.jsonl.gz` (scan-session-replay PRD
 * Phase 1). JVM targets use java.util.zip; iOS uses platform.zlib with
 * gzip window bits.
 */
expect fun gzipBytes(data: ByteArray): ByteArray
