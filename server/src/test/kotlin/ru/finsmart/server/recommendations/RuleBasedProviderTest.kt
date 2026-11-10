package ru.finsmart.server.recommendations

import ru.finsmart.server.common.LlmConfig
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class RuleBasedProviderTest {
    private val provider = RuleBasedProvider()

    @Test
    fun `uses category with largest increase instead of largest current total`() = runBlocking {
        val result = provider.recommend(
            input(
                change = 12.0,
                topCategory = "groceries",
                categories = listOf(
                    CategoryChange("groceries", BigDecimal("100.00")),
                    CategoryChange("home", BigDecimal("250.00")),
                ),
            ),
        )

        assertEquals("home", result.category)
        assertEquals("open_offers", result.actionType)
        assertEquals("Посмотреть скидки", result.actionLabel)
        assertEquals("rules", result.source)
        assertEquals(true, result.message.contains("Дом"))
        assertEquals(false, result.message.contains("home"))
    }

    @Test
    fun `does not invent a comparison without previous period`() = runBlocking {
        val result = provider.recommend(input(change = null, topCategory = "groceries"))

        assertEquals("Пока недостаточно данных", result.title)
        assertEquals(null, result.category)
        assertEquals("none", result.actionType)
    }

    @Test
    fun `returns neutral recommendation when expenses did not grow`() = runBlocking {
        val result = provider.recommend(input(change = -3.0, topCategory = "home"))

        assertEquals("Расходы не выросли", result.title)
        assertEquals(null, result.category)
        assertEquals("open_transactions", result.actionType)
    }

    @Test
    fun `rejects an LLM response with an unknown field`() {
        val provider = LlmProvider(LlmConfig())

        assertFailsWith<IllegalArgumentException> {
            provider.parseResponse(
                """{"title":"Совет","message":"Текст","category":"home","actionType":"open_offers","actionLabel":"Скидки","extra":true}""",
            )
        }
    }

    @Test
    fun `fallback keeps source returned by a non rule provider`() = runBlocking {
        val llm = object : RecommendationProvider {
            override suspend fun recommend(input: RecommendationInput) = RecommendationResponse(
                "Совет", "Текст", "home", "open_offers", "Скидки", "llm",
            )
        }
        val result = FallbackRecommendationProvider(llm, provider).recommend(input(change = 1.0))

        assertEquals("llm", result.source)
    }

    private fun input(
        change: Double?,
        topCategory: String? = null,
        categories: List<CategoryChange> = emptyList(),
    ) = RecommendationInput(
        totalExpense = "100.00",
        previousTotalExpense = "90.00",
        changePercent = change,
        topCategory = topCategory,
        goalProgressPercent = 50.0,
        categories = categories,
    )
}
