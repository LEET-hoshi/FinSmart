package ru.finsmart.server.analytics

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import ru.finsmart.server.auth.userId
import ru.finsmart.server.common.Params
import ru.finsmart.server.common.percentChange
import ru.finsmart.server.common.percentShare
import ru.finsmart.server.common.toMoneyString
import ru.finsmart.server.common.transaction
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class CategorySummaryResponse(
    val category: String,
    val amount: String,
    val previousAmount: String,
    val changePercent: Double?,
    val sharePercent: Double,
)

@Serializable
data class AnalyticsSummaryResponse(
    val month: String,
    val previousMonth: String,
    val totalExpense: String,
    val previousTotalExpense: String,
    val changePercent: Double?,
    val topCategory: String?,
    val transactionsCount: Long,
    val categories: List<CategorySummaryResponse>,
)

data class CategoryExpense(
    val category: String,
    val current: BigDecimal,
    val previous: BigDecimal,
    val currentCount: Long,
)

class AnalyticsRepository(private val dataSource: DataSource) {
    suspend fun expensesByCategory(userId: UUID, month: YearMonth): List<CategoryExpense> =
        dataSource.transaction { conn ->
            val currentStart = month.atDay(1)
            val previousStart = month.minusMonths(1).atDay(1)
            val nextStart = month.plusMonths(1).atDay(1)
            conn.prepareStatement(
                """
                SELECT category_code,
                       COALESCE(SUM(-amount) FILTER (WHERE operation_date >= ?), 0) AS current_amount,
                       COALESCE(SUM(-amount) FILTER (WHERE operation_date < ?), 0) AS previous_amount,
                       COUNT(*) FILTER (WHERE operation_date >= ?) AS current_count
                FROM transactions
                WHERE user_id = ? AND amount < 0 AND operation_date >= ? AND operation_date < ?
                GROUP BY category_code
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, currentStart)
                ps.setObject(2, currentStart)
                ps.setObject(3, currentStart)
                ps.setObject(4, userId)
                ps.setObject(5, previousStart)
                ps.setObject(6, nextStart)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                CategoryExpense(
                                    category = rs.getString("category_code"),
                                    current = rs.getBigDecimal("current_amount"),
                                    previous = rs.getBigDecimal("previous_amount"),
                                    currentCount = rs.getLong("current_count"),
                                ),
                            )
                        }
                    }
                }
            }
        }
}

class AnalyticsService(private val repository: AnalyticsRepository) {
    suspend fun summary(userId: UUID, month: YearMonth): AnalyticsSummaryResponse {
        val expenses = repository.expensesByCategory(userId, month)
            .sortedWith(
                compareByDescending<CategoryExpense> { it.current }
                    .thenByDescending { it.previous }
                    .thenBy { it.category },
            )
        val total = expenses.fold(BigDecimal.ZERO) { acc, e -> acc + e.current }
        val previousTotal = expenses.fold(BigDecimal.ZERO) { acc, e -> acc + e.previous }

        return AnalyticsSummaryResponse(
            month = month.toString(),
            previousMonth = month.minusMonths(1).toString(),
            totalExpense = total.toMoneyString(),
            previousTotalExpense = previousTotal.toMoneyString(),
            changePercent = percentChange(total, previousTotal),
            topCategory = expenses.firstOrNull { it.current.signum() > 0 }?.category,
            transactionsCount = expenses.sumOf { it.currentCount },
            categories = expenses.map {
                CategorySummaryResponse(
                    category = it.category,
                    amount = it.current.toMoneyString(),
                    previousAmount = it.previous.toMoneyString(),
                    changePercent = percentChange(it.current, it.previous),
                    sharePercent = percentShare(it.current, total),
                )
            },
        )
    }
}

fun Route.analyticsRoutes(service: AnalyticsService) {
    get("/analytics/summary") {
        val month = Params.requiredMonth(call.request.queryParameters["month"])
        call.respond(service.summary(call.userId(), month))
    }
}
