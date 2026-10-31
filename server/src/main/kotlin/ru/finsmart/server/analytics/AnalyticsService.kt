package ru.finsmart.server.analytics

import kotlinx.serialization.Serializable
import ru.finsmart.server.common.MoneySerializer
import ru.finsmart.server.common.percent
import java.math.BigDecimal
import java.time.YearMonth

@Serializable
data class CategorySummary(
    val category: String,
    @Serializable(with = MoneySerializer::class) val amount: BigDecimal,
    @Serializable(with = MoneySerializer::class) val previousAmount: BigDecimal,
    val changePercent: Double?,
    val sharePercent: Double,
)

@Serializable
data class AnalyticsSummary(
    val month: String,
    val previousMonth: String,
    @Serializable(with = MoneySerializer::class) val totalExpense: BigDecimal,
    @Serializable(with = MoneySerializer::class) val previousTotalExpense: BigDecimal,
    val changePercent: Double?,
    val topCategory: String?,
    val transactionsCount: Int,
    val categories: List<CategorySummary>,
)

/** Чистый расчёт сводки по уже агрегированным расходам (суммы по модулю). Финансы считает код, не ИИ. */
object AnalyticsCalculator {
    fun summarize(
        month: YearMonth,
        current: Map<String, BigDecimal>,
        previous: Map<String, BigDecimal>,
        transactionsCount: Int,
    ): AnalyticsSummary {
        val total = current.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val previousTotal = previous.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val categories = (current.keys + previous.keys)
            .map { code ->
                val amount = current[code] ?: BigDecimal.ZERO
                val prev = previous[code] ?: BigDecimal.ZERO
                CategorySummary(
                    category = code,
                    amount = amount,
                    previousAmount = prev,
                    changePercent = change(amount, prev),
                    sharePercent = if (total.signum() > 0) percent(amount, total) else 0.0,
                )
            }
            .sortedWith(compareByDescending<CategorySummary> { it.amount }.thenBy { it.category })
        return AnalyticsSummary(
            month = month.toString(),
            previousMonth = month.minusMonths(1).toString(),
            totalExpense = total,
            previousTotalExpense = previousTotal,
            changePercent = change(total, previousTotal),
            topCategory = categories.firstOrNull()?.takeIf { it.amount.signum() > 0 }?.category,
            transactionsCount = transactionsCount,
            categories = categories,
        )
    }

    /** null, если в прошлом периоде расходов не было. */
    private fun change(current: BigDecimal, previous: BigDecimal): Double? =
        if (previous.signum() > 0) percent(current - previous, previous) else null
}
