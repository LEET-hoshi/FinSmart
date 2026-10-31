package ru.finsmart.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.UNIQUE_VIOLATION
import ru.finsmart.server.common.tx
import ru.finsmart.server.common.validationError
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class Credentials(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserDto(val id: String, val email: String)

@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto,
)

private val emailPattern = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
private val random = SecureRandom()
private const val BCRYPT_COST = 12

// Для выравнивания времени ответа, когда пользователя с таким email нет.
private val dummyHash: String = BCrypt.withDefaults().hashToString(BCRYPT_COST, "dummy-password".toCharArray())

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun newRefreshToken(): String =
    ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun invalidCredentials() =
    ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Неверный email или пароль")

private fun invalidRefresh() =
    ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Сессия истекла, войдите заново")

private fun validate(c: Credentials) {
    val email = c.email.trim()
    if (email.length > 254 || !emailPattern.matches(email)) throw validationError("email", "Некорректный email")
    if (c.password.length !in 8..128) throw validationError("password", "Пароль от 8 до 128 символов")
}

fun Route.authRoutes(dataSource: DataSource, jwt: JwtSettings) {
    fun tokens(userId: String, email: String, refreshToken: String) = AuthTokens(
        accessToken = jwt.issueAccessToken(UUID.fromString(userId)),
        refreshToken = refreshToken,
        expiresIn = jwt.accessTtl.seconds,
        user = UserDto(userId, email),
    )

    fun issueRefresh(c: java.sql.Connection, userId: String): String {
        val token = newRefreshToken()
        c.prepareStatement("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?::uuid, ?, ?)").use {
            it.setString(1, userId)
            it.setString(2, sha256(token))
            it.setTimestamp(3, Timestamp.from(Instant.now().plus(jwt.refreshTtl)))
            it.executeUpdate()
        }
        return token
    }

    post("/auth/register") {
        val body = call.receive<Credentials>()
        validate(body)
        val hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, body.password.toCharArray())
        val result = try {
            dataSource.tx { c ->
                val id = c.prepareStatement("INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id").use {
                    it.setString(1, body.email.trim())
                    it.setString(2, hash)
                    it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
                tokens(id, body.email.trim(), issueRefresh(c, id))
            }
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) {
                throw ApiException(HttpStatusCode.Conflict, "email_taken", "Пользователь с таким email уже существует")
            }
            throw e
        }
        call.respond(HttpStatusCode.Created, result)
    }

    post("/auth/login") {
        val body = call.receive<Credentials>()
        validate(body)
        val found = dataSource.tx { c ->
            c.prepareStatement("SELECT id, email, password_hash FROM users WHERE lower(email) = lower(?)").use {
                it.setString(1, body.email.trim())
                it.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
            }
        }
        val verified = BCrypt.verifyer().verify(body.password.toCharArray(), found?.third ?: dummyHash).verified
        if (found == null || !verified) throw invalidCredentials()
        val result = dataSource.tx { c -> tokens(found.first, found.second, issueRefresh(c, found.first)) }
        call.respond(result)
    }

    post("/auth/refresh") {
        val body = call.receive<RefreshRequest>()
        val user = dataSource.tx { c ->
            c.prepareStatement(
                """SELECT u.id, u.email FROM refresh_tokens t JOIN users u ON u.id = t.user_id
                   WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > now()""",
            ).use {
                it.setString(1, sha256(body.refreshToken))
                it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
            }
        } ?: throw invalidRefresh()
        // Refresh-токен не ротируется (ADR 0001): возвращаем тот же.
        call.respond(tokens(user.first, user.second, body.refreshToken))
    }

    post("/auth/logout") {
        val body = call.receive<RefreshRequest>()
        dataSource.tx { c ->
            c.prepareStatement("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL").use {
                it.setString(1, sha256(body.refreshToken))
                it.executeUpdate()
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
