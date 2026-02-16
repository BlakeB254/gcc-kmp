package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class RouteBountyPrompt(
    val id: Int,
    val routeId: Int,
    val title: String,
    val description: String,
    val pointValue: Int = 10,
    val active: Boolean = true,
)
