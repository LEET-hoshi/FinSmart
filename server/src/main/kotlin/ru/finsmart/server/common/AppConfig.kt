package ru.finsmart.server.common

import java.time.Duration

data class AppConfig(
    val port: Int,
    val db: DbConfig,
    val jwt: JwtConfig,
    val offersFile: String? = null,
    val rateLimit: RateLimitConfig = RateLimitConfig(),
    val llm: LlmConfig = LlmConfig(),
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
            rateLimit = RateLimitConfig(
                authPerMinute = env.positiveInt("RATE_LIMIT_AUTH_PER_MINUTE") ?: RateLimitConfig.DEFAULT_AUTH_PER_MINUTE,
                importPerMinute = env.positiveInt("RATE_LIMIT_IMPORT_PER_MINUTE") ?: RateLimitConfig.DEFAULT_IMPORT_PER_MINUTE,
            ),
            llm = LlmConfig(
                enabled = env["LLM_ENABLED"].equals("true", ignoreCase = true),
                endpoint = env["LLM_ENDPOINT"]?.takeIf { it.isNotBlank() },
                apiKey = env["LLM_API_KEY"]?.takeIf { it.isNotBlank() },
                timeout = Duration.ofSeconds(env["LLM_TIMEOUT_SECONDS"]?.toLongOrNull()?.coerceIn(1, 30) ?: 5),
            ),
        )

        private fun Map<String, String>.positiveInt(key: String): Int? = this[key]?.toIntOrNull()?.takeIf { it > 0 }
    }
}

data class LlmConfig(
    val enabled: Boolean = false,
    val endpoint: String? = null,
    val apiKey: String? = null,
    val timeout: Duration = Duration.ofSeconds(5),
) {
    val mode: String get() = if (enabled && endpoint != null) "llm" else "rules"
}

data class RateLimitConfig(
    val authPerMinute: Int = DEFAULT_AUTH_PER_MINUTE,
    val importPerMinute: Int = DEFAULT_IMPORT_PER_MINUTE,
) {
    companion object {
        const val DEFAULT_AUTH_PER_MINUTE = 10
        const val DEFAULT_IMPORT_PER_MINUTE = 10
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
