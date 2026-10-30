package ru.finsmart.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import ru.finsmart.server.common.ApiError
import ru.finsmart.server.common.ApiException
import java.util.UUID

const val JWT_AUTH = "jwt"

fun Application.configureJwtAuth(tokens: TokenService) {
    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = "finsmart"
            verifier(tokens.verifier)
            validate { credential ->
                val subject = credential.payload.subject
                val userId = subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (userId != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(code = "unauthorized", message = "Требуется авторизация"),
                )
            }
        }
    }
}

fun ApplicationCall.userId(): UUID =
    principal<JWTPrincipal>()?.subject?.let { UUID.fromString(it) } ?: throw ApiException.unauthorized()
