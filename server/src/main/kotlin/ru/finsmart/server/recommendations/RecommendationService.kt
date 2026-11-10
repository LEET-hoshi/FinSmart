package ru.finsmart.server.recommendations

import ru.finsmart.server.analytics.AnalyticsService
import ru.finsmart.server.common.transaction
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource

class RecommendationService(
    private val analyticsService: AnalyticsService,
    private val goalProgressService: GoalProgressService,
    private val provider: RecommendationProvider,
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
            goalProgressPercent = goalProgressService.forMonth(userId, month),
            categories = analytics.categories.map {
                CategoryChange(it.category, BigDecimal(it.amount).subtract(BigDecimal(it.previousAmount)))
            },
        )

        return provider.recommend(input)
    }
}

/**
 * Supplies one concise goal signal to a provider. It uses only stored goal amounts
 * and category aggregates for the requested month, never transactions or CSV text.
 */
class GoalProgressService(
    private val dataSource: DataSource,
    private val analyticsService: AnalyticsService,
) {
    suspend fun forMonth(userId: UUID, month: YearMonth): Double? {
        val categoryExpenses = analyticsService.summary(userId, month).categories
            .associate { it.category to BigDecimal(it.amount) }
        val goals = dataSource.transaction { connection ->
            connection.prepareStatement(
                "SELECT type, target_amount, current_amount, category_code FROM goals " +
                    "WHERE user_id = ? AND deleted_at IS NULL",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val type = result.getString("type")
                            val target = result.getBigDecimal("target_amount")
                            val current = if (type == "spending_limit") {
                                categoryExpenses[result.getString("category_code")] ?: BigDecimal.ZERO
                            } else {
                                result.getBigDecimal("current_amount")
                            }
                            add(current.multiply(BigDecimal(100)).divide(target, 1, java.math.RoundingMode.HALF_UP).toDouble().coerceAtMost(100.0))
                        }
                    }
                }
            }
        }
        // The goal closest to completion is the clearest single progress signal for one card.
        return goals.maxOrNull()
    }
}
