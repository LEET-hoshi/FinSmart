package ru.finsmart.server.recommendations

import ru.finsmart.server.analytics.AnalyticsService
import java.time.YearMonth
import java.util.UUID

class RecommendationService(
    private val analyticsService: AnalyticsService,
    private val provider: RecommendationProvider
) {
    suspend fun recommendation(
        userId: UUID,
        month: YearMonth
    ): RecommendationResponse {
        val analytics = analyticsService.summary(userId, month)

        val input = RecommendationInput(
            totalExpense = analytics.totalExpense,
            previousTotalExpense = analytics.previousTotalExpense,
            changePercent = analytics.changePercent,
            topCategory = analytics.topCategory,
            goalProgressPercent = null
        )

        return provider.recommend(input).copy(source = "rules")
    }
}
