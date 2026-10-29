package ru.finsmart.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import ru.finsmart.server.common.ApiError
import ru.finsmart.server.common.AppConfig
import ru.finsmart.server.common.Database
import ru.finsmart.server.health.healthRoutes
import javax.sql.DataSource

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = Database.createDataSource(config.db)
    Database.migrate(dataSource)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(dataSource)
    }.start(wait = true)
}

fun Application.module(dataSource: DataSource) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(code = "internal_error", message = "Внутренняя ошибка сервера"),
            )
        }
    }

    routing {
        healthRoutes(dataSource)
    }
}
