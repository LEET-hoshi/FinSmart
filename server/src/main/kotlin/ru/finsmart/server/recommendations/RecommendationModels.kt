package ru.finsmart.server.recommendations

import kotlinx.serialization.Serializable

@Serializable
data class RecommendationResponse(
    val title: String,
    val message: String,
    val category: String?,
    val actionType: String,
    val actionLabel: String?,
    val source: String
)