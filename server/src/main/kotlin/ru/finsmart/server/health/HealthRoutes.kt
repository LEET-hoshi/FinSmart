package ru.finsmart.server.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
)

fun Route.healthRoutes(dataSource: DataSource) {
    get("/health") {
        val dbOk = runCatching {
            withContext(Dispatchers.IO) {
                dataSource.connection.use { it.isValid(2) }
            }
        }.getOrDefault(false)

        if (dbOk) {
            call.respond(HealthResponse(status = "ok", database = "ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "degraded", database = "unavailable"))
        }
    }
}
