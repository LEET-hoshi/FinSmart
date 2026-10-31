package ru.finsmart.server.recommendations

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import ru.finsmart.server.auth.userId
import ru.finsmart.server.common.Params
import java.time.YearMonth

fun Route.recommendationRoutes(service: RecommendationService) {
    get("/ai/recommendation") {
        val month = Params.requiredMonth(call.request.queryParameters["month"])
        val recommendation = service.recommendation(
            userId = call.userId(),
            month = month
        )
        call.respond(recommendation)
    }
}