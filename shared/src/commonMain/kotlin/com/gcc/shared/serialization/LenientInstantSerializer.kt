package com.gcc.shared.serialization

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Instant (de)serializer tolerant of Postgres timestamp formats.
 *
 * The site's API routes occasionally leak raw Postgres timestamps
 * ("2026-07-03 15:35:28.218012+00") instead of ISO-8601 — one such string in
 * one row took down the entire bounties tab (2026-07-03 field report). This
 * serializer normalizes the common variants instead of crashing the screen:
 *  - space date/time separator → 'T'
 *  - bare "+00" / "+0000" style offsets → "+00:00"
 *  - missing offset entirely → assume UTC
 *
 * Applied via `@file:UseSerializers(LenientInstantSerializer::class)` in the
 * shared model files, so every Instant field gets it.
 */
object LenientInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.gcc.LenientInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant =
        parseFlexibleInstant(decoder.decodeString())
}

/** Parse an ISO-8601 or Postgres-style timestamp into an Instant. */
fun parseFlexibleInstant(raw: String): Instant {
    val trimmed = raw.trim()
    runCatching { return Instant.parse(trimmed) }

    var normalized = trimmed.replaceFirst(' ', 'T')

    // Normalize the offset suffix.
    val offsetMatch = Regex("([+-])(\\d{2})(:?)(\\d{2})?$").find(normalized)
    normalized = when {
        normalized.endsWith("Z") -> normalized
        offsetMatch != null -> {
            val (sign, hours, _, minutes) = offsetMatch.destructured
            normalized.removeRange(offsetMatch.range) +
                "$sign$hours:${minutes.ifEmpty { "00" }}"
        }
        else -> normalized + "Z"
    }
    return Instant.parse(normalized)
}
