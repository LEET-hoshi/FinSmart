package ru.finsmart.server.auth

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.finsmart.server.common.ApiException

class AuthService(
    private val repository: AuthRepository,
    private val tokens: TokenService,
) {
    suspend fun register(request: CredentialsRequest): AuthTokensResponse {
        val (email, password) = validate(request)
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
        val user = repository.createUser(email, hash)
            ?: throw ApiException(HttpStatusCode.Conflict, "email_taken", "Пользователь с таким email уже существует")
        return issueTokens(user)
    }

    suspend fun login(request: CredentialsRequest): AuthTokensResponse {
        val (email, password) = validate(request)
        val user = repository.findByEmail(email)
        val valid = withContext(Dispatchers.Default) {
            if (user == null) {
                PasswordHasher.verifyAgainstDummy(password)
                false
            } else {
                PasswordHasher.verify(password, user.passwordHash)
            }
        }
        if (user == null || !valid) {
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Неверный email или пароль")
        }
        return issueTokens(user)
    }

    suspend fun refresh(request: RefreshRequest): AuthTokensResponse {
        val refreshToken = request.refreshToken?.takeIf { it.isNotBlank() } ?: throw invalidRefreshToken()
        val user = repository.findActiveRefreshTokenOwner(tokens.hashRefreshToken(refreshToken))
            ?: throw invalidRefreshToken()
        return tokensResponse(user, refreshToken)
    }

    suspend fun logout(request: RefreshRequest) {
        val refreshToken = request.refreshToken?.takeIf { it.isNotBlank() } ?: return
        repository.revokeRefreshToken(tokens.hashRefreshToken(refreshToken))
    }

    private suspend fun issueTokens(user: UserRecord): AuthTokensResponse {
        val refreshToken = tokens.newRefreshToken()
        repository.saveRefreshToken(user.id, tokens.hashRefreshToken(refreshToken), tokens.refreshTtlSeconds)
        return tokensResponse(user, refreshToken)
    }

    private fun tokensResponse(user: UserRecord, refreshToken: String) = AuthTokensResponse(
        accessToken = tokens.createAccessToken(user.id),
        refreshToken = refreshToken,
        tokenType = "Bearer",
        expiresIn = tokens.accessTtlSeconds,
        user = UserResponse(id = user.id.toString(), email = user.email),
    )

    private fun invalidRefreshToken() =
        ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Сессия истекла, войдите заново")

    private fun validate(request: CredentialsRequest): Pair<String, String> {
        val email = request.email?.trim()?.lowercase()
        val password = request.password
        val errors = mutableMapOf<String, String>()

        when {
            email.isNullOrEmpty() -> errors["email"] = "Укажите email"
            email.length > MAX_EMAIL_LENGTH || !EMAIL_REGEX.matches(email) -> errors["email"] = "Некорректный email"
        }
        when {
            password.isNullOrEmpty() -> errors["password"] = "Укажите пароль"
            password.length < MIN_PASSWORD_LENGTH -> errors["password"] = "Пароль должен быть не короче $MIN_PASSWORD_LENGTH символов"
            password.length > MAX_PASSWORD_LENGTH -> errors["password"] = "Пароль должен быть не длиннее $MAX_PASSWORD_LENGTH символов"
        }

        if (errors.isNotEmpty()) throw ApiException.validation(errors)
        return email!! to password!!
    }

    private companion object {
        const val MAX_EMAIL_LENGTH = 254
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 128
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
