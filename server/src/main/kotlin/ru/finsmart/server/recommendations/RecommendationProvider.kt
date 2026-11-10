package ru.finsmart.server.recommendations

import java.math.BigDecimal

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
    val goalProgressPercent: Double?,
    /** Categories from the server aggregate; never contains transaction descriptions or CSV rows. */
    val categories: List<CategoryChange> = emptyList(),
)

data class CategoryChange(
    val code: String,
    val growthAmount: BigDecimal,
)

