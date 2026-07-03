@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Media(
    val id: Int,
    val url: String,
    val filename: String,
    val mimeType: String,
    val alt: String? = null,
    val category: MediaCategory = MediaCategory.OTHER,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val uploadedBy: Int? = null,
    val createdAt: Instant
)
