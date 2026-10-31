package ru.finsmart.server.recommendations

class RuleBasedProvider : RecommendationProvider {

    override suspend fun recommend(
        input: RecommendationInput
    ): RecommendationResponse {
        val change = input.changePercent

        if (change == null) {
            return RecommendationResponse(
                title = "Пока недостаточно данных",
                message = "Продолжайте учитывать расходы, чтобы получать рекомендации на основе финансовой истории.",
                category = input.topCategory,
                actionType = "none",
                actionLabel = null,
                source = "rules"
            )
        }

        if (change > 0.0) {
            return RecommendationResponse(
                title = "Расходы выросли",
                message = if (input.topCategory != null) {
                    "Проверьте расходы в категории ${input.topCategory} и сравните их с предыдущим месяцем."
                } else {
                    "Сравните расходы по категориям и проверьте, на чём можно сэкономить."
                },
                category = input.topCategory,
                actionType = "open_transactions",
                actionLabel = "Посмотреть расходы",
                source = "rules"
            )
        }

        return RecommendationResponse(
            title = "Расходы не выросли",
            message = "Сравните расходы по категориям и продолжайте контролировать бюджет.",
            category = input.topCategory,
            actionType = "open_transactions",
            actionLabel = "Посмотреть расходы",
            source = "rules"
        )
    }
}


