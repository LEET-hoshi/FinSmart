package ru.finsmart.server.recommendations

class RuleBasedProvider : RecommendationProvider {

    override suspend fun recommend(
        input: RecommendationInput
    ): RecommendationResponse {
        val change = input.changePercent

        if (change == null) {
            return RecommendationResponse(
                title = "Пока недостаточно данных",
                message = "Продолжайте учитывать расходы, чтобы получать рекомендации на основе финансовой истории." + goalProgressSuffix(input),
                category = null,
                actionType = "none",
                actionLabel = null,
                source = "rules"
            )
        }

        if (change > 0.0) {
            // topCategory is the largest current total, which can be unrelated to the
            // category whose spending increased the most compared with the previous month.
            val category = input.categories
                .asSequence()
                .filter { it.growthAmount.signum() > 0 }
                .sortedWith(compareByDescending<CategoryChange> { it.growthAmount }.thenBy { it.code })
                .firstOrNull()
                ?.code

            if (category == null) {
                return RecommendationResponse(
                    title = "Расходы выросли",
                    message = "Сравните расходы по категориям и проверьте, на чём можно сэкономить." + goalProgressSuffix(input),
                    category = null,
                    actionType = "open_transactions",
                    actionLabel = "Посмотреть расходы",
                    source = "rules",
                )
            }

            return RecommendationResponse(
                title = "Расходы выросли",
                message = "Расходы в категории «${categoryName(category)}» выросли сильнее всего. " +
                    "Посмотрите скидки в этой категории." + goalProgressSuffix(input),
                category = category,
                actionType = "open_offers",
                actionLabel = "Посмотреть скидки",
                source = "rules"
            )
        }

        return RecommendationResponse(
            title = "Расходы не выросли",
            message = "Сравните расходы по категориям и продолжайте контролировать бюджет." + goalProgressSuffix(input),
            category = null,
            actionType = "open_transactions",
            actionLabel = "Посмотреть расходы",
            source = "rules"
        )
    }

    private fun goalProgressSuffix(input: RecommendationInput): String =
        input.goalProgressPercent?.let { " Прогресс по ближайшей цели: ${"%.1f".format(java.util.Locale.US, it)}%." } ?: ""

    private fun categoryName(code: String): String = CATEGORY_NAMES[code] ?: "выбранной категории"

    private companion object {
        val CATEGORY_NAMES = mapOf(
            "groceries" to "Продукты",
            "cafes" to "Кафе и рестораны",
            "transport" to "Транспорт",
            "health" to "Здоровье",
            "home" to "Дом",
            "entertainment" to "Развлечения",
            "other" to "Прочее",
        )
    }
}


