package ru.finsmart.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ru.finsmart.server.common.receiveJson

fun Route.authRoutes(service: AuthService) {
    route("/auth") {
        post("/register") {
            val response = service.register(call.receiveJson<CredentialsRequest>())
            call.respond(HttpStatusCode.Created, response)
        }
        post("/login") {
            call.respond(service.login(call.receiveJson<CredentialsRequest>()))
        }
        post("/refresh") {
            call.respond(service.refresh(call.receiveJson<RefreshRequest>()))
        }
        post("/logout") {
            service.logout(call.receiveJson<RefreshRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
