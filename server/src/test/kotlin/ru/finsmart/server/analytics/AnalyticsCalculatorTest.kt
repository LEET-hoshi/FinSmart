package ru.finsmart.server.analytics

import java.math.BigDecimal
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsCalculatorTest {
    private val october = YearMonth.of(2026, 10)

    private fun money(text: String) = BigDecimal(text)

    @Test
    fun summary_compares_with_previous_month_and_sorts_by_current_amount() {
        val summary = AnalyticsCalculator.summarize(
            october,
            current = mapOf("groceries" to money("4800.00"), "cafes" to money("1200.00")),
            previous = mapOf("groceries" to money("3900.00"), "cafes" to money("1500.00")),
            transactionsCount = 9,
        )

        assertEquals("2026-09", summary.previousMonth)
        assertEquals(money("6000.00"), summary.totalExpense)
        assertEquals(money("5400.00"), summary.previousTotalExpense)
        assertEquals(11.1, summary.changePercent)
        assertEquals("groceries", summary.topCategory)
        assertEquals(listOf("groceries", "cafes"), summary.categories.map { it.category })
        assertEquals(23.1, summary.categories[0].changePercent)
        assertEquals(-20.0, summary.categories[1].changePercent)
        assertEquals(80.0, summary.categories[0].sharePercent)
    }

    @Test
    fun change_percent_is_null_when_previous_month_has_no_expenses() {
        val summary = AnalyticsCalculator.summarize(october, mapOf("transport" to money("6200.00")), emptyMap(), 3)

        assertNull(summary.changePercent)
        assertNull(summary.categories.single().changePercent)
        assertEquals(money("0"), summary.previousTotalExpense)
    }

    @Test
    fun category_with_expenses_only_in_previous_month_is_listed_with_minus_100_percent() {
        val summary = AnalyticsCalculator.summarize(
            october,
            current = mapOf("groceries" to money("100.00")),
            previous = mapOf("health" to money("500.00")),
            transactionsCount = 1,
        )

        val health = summary.categories.single { it.category == "health" }
        assertEquals(-100.0, health.changePercent)
        assertEquals(0.0, health.sharePercent)
    }

    @Test
    fun no_expenses_gives_no_top_category() {
        val summary = AnalyticsCalculator.summarize(october, emptyMap(), emptyMap(), 0)

        assertNull(summary.topCategory)
        assertNull(summary.changePercent)
        assertEquals(emptyList(), summary.categories)
    }
}
