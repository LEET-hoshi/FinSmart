package ru.finsmart.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import ru.finsmart.server.auth.AuthRepository
import ru.finsmart.server.auth.AuthService
import ru.finsmart.server.auth.TokenService
import ru.finsmart.server.auth.authRoutes
import ru.finsmart.server.auth.configureJwtAuth
import ru.finsmart.server.common.ApiError
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.AppConfig
import ru.finsmart.server.common.Database
import ru.finsmart.server.health.healthRoutes
import javax.sql.DataSource

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.createDataSource(config.db)
    Database.migrate(dataSource)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, dataSource)
    }.start(wait = true)
}

fun Application.module(config: AppConfig, dataSource: DataSource) {
    val tokens = TokenService(config.jwt)
    val authService = AuthService(AuthRepository(dataSource), tokens)

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
    }
}
