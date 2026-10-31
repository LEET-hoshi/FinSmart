package ru.finsmart.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import ru.finsmart.server.analytics.AnalyticsRepository
import ru.finsmart.server.analytics.AnalyticsService
import ru.finsmart.server.analytics.analyticsRoutes
import ru.finsmart.server.auth.AuthRepository
import ru.finsmart.server.auth.AuthService
import ru.finsmart.server.auth.JWT_AUTH
import ru.finsmart.server.auth.TokenService
import ru.finsmart.server.auth.authRoutes
import ru.finsmart.server.auth.configureJwtAuth
import ru.finsmart.server.categories.CategoryRepository
import ru.finsmart.server.categories.categoryRoutes
import ru.finsmart.server.common.ApiError
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.AppConfig
import ru.finsmart.server.common.Database
import ru.finsmart.server.goals.goalRoutes
import ru.finsmart.server.health.healthRoutes
import ru.finsmart.server.offers.OfferCatalogLoader
import ru.finsmart.server.offers.offerRoutes
import ru.finsmart.server.transactions.TransactionRepository
import ru.finsmart.server.transactions.TransactionService
import ru.finsmart.server.transactions.transactionRoutes
import java.nio.file.Path
import javax.sql.DataSource
import ru.finsmart.server.recommendations.RecommendationService
import ru.finsmart.server.recommendations.RuleBasedProvider
import ru.finsmart.server.recommendations.recommendationRoutes

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.createDataSource(config.db)
    Database.migrate(dataSource)
    config.offersFile?.let { OfferCatalogLoader.load(dataSource, Path.of(it)) }

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, dataSource)
    }.start(wait = true)
}

fun Application.module(config: AppConfig, dataSource: DataSource) {
    val tokens = TokenService(config.jwt)
    val authService = AuthService(AuthRepository(dataSource), tokens)
    val transactionService = TransactionService(TransactionRepository(dataSource))
    val analyticsRepository = AnalyticsRepository(dataSource)
    val analyticsService = AnalyticsService(analyticsRepository)
    val recommendationService = RecommendationService(
    analyticsService,
    RuleBasedProvider()
)
    val categoryRepository = CategoryRepository(dataSource)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiError(cause.code, cause.message, cause.details))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(code = "validation_error", message = "Некорректное тело запроса"),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiError(code = "not_found", message = "Объект не найден"),
            )
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ApiError(code = "unsupported_media_type", message = "Ожидается Content-Type: application/json"),
            )
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(code = "payload_too_large", message = "Слишком большой запрос"),
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(code = "validation_error", message = "Некорректное тело запроса"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(code = "internal_error", message = "Внутренняя ошибка сервера"),
            )
        }
    }
    configureJwtAuth(tokens)

    routing {
        healthRoutes(dataSource)
        authRoutes(authService)
        categoryRoutes(categoryRepository)
        authenticate(JWT_AUTH) {
            transactionRoutes(transactionService)
            analyticsRoutes(analyticsService)
            recommendationRoutes(recommendationService)
            goalRoutes(dataSource, analyticsRepository)
            offerRoutes(dataSource)
        }
    }
}
