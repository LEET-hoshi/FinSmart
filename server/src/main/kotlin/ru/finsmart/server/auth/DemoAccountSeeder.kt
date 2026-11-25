package ru.finsmart.server.auth

import ru.finsmart.server.common.DemoAccountConfig
import java.util.Locale
import javax.sql.DataSource

/**
 * Creates an explicitly configured demo user for a local MVP environment.
 * It is idempotent and never overwrites a password chosen for that account.
 */
object DemoAccountSeeder {
    fun seed(dataSource: DataSource, config: DemoAccountConfig) {
        if (!config.enabled) return

        val email = config.email.trim().lowercase(Locale.ROOT)
        require(email.isNotBlank() && email.length <= 254 && '@' in email) {
            "DEMO_ACCOUNT_EMAIL has an invalid value"
        }
        require(config.password.length >= 8) {
            "DEMO_ACCOUNT_PASSWORD must contain at least 8 characters"
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (email, password_hash) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, email)
                statement.setString(2, PasswordHasher.hash(config.password))
                statement.executeUpdate()
            }
        }
    }
}
