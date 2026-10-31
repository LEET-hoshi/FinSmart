package ru.finsmart.server.recommendations

interface RecommendationProvider {
    suspend fun recommend(
        input: RecommendationInput
    ): RecommendationResponse
}

data class RecommendationInput(
    val totalExpense: String,
    val previousTotalExpense: String,
    val changePercent: Double?,
    val topCategory: String?,
    val goalProgressPercent: Double?
)

