package ru.finsmart.server.common

data class AppConfig(
    val port: Int,
    val db: DbConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            db = DbConfig(
                url = env["DB_URL"] ?: "jdbc:postgresql://localhost:5432/finsmart",
                user = env["DB_USER"] ?: "finsmart",
                password = env["DB_PASSWORD"] ?: "finsmart",
            ),
        )
    }
}

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
)
