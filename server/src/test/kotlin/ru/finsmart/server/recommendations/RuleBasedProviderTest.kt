package ru.finsmart.server.recommendations

import ru.finsmart.server.common.LlmConfig
import java.math.BigDecimal
import java.net.http.HttpTimeoutException
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
    fun `accepts a valid LLM response and keeps its source`() {
        val provider = LlmProvider(LlmConfig())

        val result = provider.parseResponse(
            """{"title":"Совет","message":"Проверьте скидки на товары для дома","category":"home","actionType":"open_offers","actionLabel":"Посмотреть скидки"}""",
        )

        assertEquals("llm", result.source)
        assertEquals("home", result.category)
        assertEquals("open_offers", result.actionType)
    }

    @Test
    fun `rejects an LLM response with an invalid category`() {
        val provider = LlmProvider(LlmConfig())

        assertFailsWith<IllegalArgumentException> {
            provider.parseResponse(validLlmResponse(category = "unknown"))
        }
    }

    @Test
    fun `rejects an LLM response with an unsupported action type`() {
        val provider = LlmProvider(LlmConfig())

        assertFailsWith<IllegalArgumentException> {
            provider.parseResponse(validLlmResponse(actionType = "delete_account"))
        }
    }

    @Test
    fun `rejects open offers LLM response without a category`() {
        val provider = LlmProvider(LlmConfig())

        assertFailsWith<IllegalArgumentException> {
            provider.parseResponse(validLlmResponse(category = null))
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

    @Test
    fun `fallback returns rules response when LLM times out`() = runBlocking {
        val timedOutLlm = object : RecommendationProvider {
            override suspend fun recommend(input: RecommendationInput): RecommendationResponse {
                throw HttpTimeoutException("LLM request timed out")
            }
        }

        val result = FallbackRecommendationProvider(timedOutLlm, provider).recommend(
            input(
                change = 3.0,
                categories = listOf(CategoryChange("home", BigDecimal("25.00"))),
            ),
        )

        assertEquals("rules", result.source)
        assertEquals("home", result.category)
        assertEquals("open_offers", result.actionType)
    }

    @Test
    fun `fallback returns rules response when LLM schema is invalid`() = runBlocking {
        val invalidLlm = LlmProvider(LlmConfig())
        val primary = object : RecommendationProvider {
            override suspend fun recommend(input: RecommendationInput): RecommendationResponse =
                invalidLlm.parseResponse("""{"title":"Совет"}""")
        }

        val result = FallbackRecommendationProvider(primary, provider).recommend(input(change = -2.0))

        assertEquals("rules", result.source)
        assertEquals("open_transactions", result.actionType)
    }

    private fun validLlmResponse(
        category: String? = "home",
        actionType: String = "open_offers",
    ): String =
        """{"title":"Совет","message":"Проверьте скидки","category":${category?.let { "\"$it\"" } ?: "null"},"actionType":"$actionType","actionLabel":"Посмотреть скидки"}"""

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

