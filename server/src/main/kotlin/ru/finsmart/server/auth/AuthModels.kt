package ru.finsmart.server.auth

import kotlinx.serialization.Serializable

@Serializable
data class CredentialsRequest(
    val email: String? = null,
    val password: String? = null,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String? = null,
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
)

@Serializable
data class AuthTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: UserResponse,
)
