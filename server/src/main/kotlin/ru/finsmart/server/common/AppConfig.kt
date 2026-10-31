package ru.finsmart.server.common

import java.time.Duration

data class AppConfig(
    val port: Int,
    val db: DbConfig,
    val jwt: JwtConfig,
    val offersFile: String? = null,
) {
    companion object {
        private const val MIN_SECRET_LENGTH = 32

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            db = DbConfig(
                url = env["DB_URL"] ?: "jdbc:postgresql://localhost:5432/finsmart",
                user = env["DB_USER"] ?: "finsmart",
                password = env["DB_PASSWORD"] ?: "finsmart",
            ),
            jwt = JwtConfig(
                secret = env["JWT_SECRET"]?.takeIf { it.length >= MIN_SECRET_LENGTH }
                    ?: error("JWT_SECRET не задан или короче $MIN_SECRET_LENGTH символов"),
                issuer = "finsmart",
                audience = "finsmart-app",
                accessTtl = Duration.ofSeconds(env["ACCESS_TOKEN_TTL_SECONDS"]?.toLongOrNull() ?: 3600),
                refreshTtl = Duration.ofDays(env["REFRESH_TOKEN_TTL_DAYS"]?.toLongOrNull() ?: 30),
            ),
            offersFile = env["OFFERS_FILE"]?.takeIf { it.isNotBlank() },
        )
    }
}

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
)
