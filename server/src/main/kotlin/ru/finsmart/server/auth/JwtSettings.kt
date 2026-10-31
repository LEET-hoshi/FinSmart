package ru.finsmart.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import ru.finsmart.server.common.unauthorized
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

class JwtSettings(
    secret: String,
    val accessTtl: Duration = Duration.ofHours(1),
    val refreshTtl: Duration = Duration.ofDays(30),
) {
    init {
        require(secret.length >= 32) { "JWT_SECRET должен быть не короче 32 символов" }
    }

    val algorithm: Algorithm = Algorithm.HMAC256(secret)

    fun issueAccessToken(userId: UUID): String = JWT.create()
        .withSubject(userId.toString())
        .withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plus(accessTtl)))
        .sign(algorithm)
}

const val AUTH = "auth"

/** Владелец данных берётся только из проверенного токена, не из тела запроса. */
fun ApplicationCall.userId(): UUID {
    val subject = authentication.principal<JWTPrincipal>()?.subject ?: throw unauthorized()
    return runCatching { UUID.fromString(subject) }.getOrElse { throw unauthorized() }
}
