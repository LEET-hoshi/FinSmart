package ru.finsmart.server.auth

import ru.finsmart.server.common.transaction
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

data class UserRecord(
    val id: UUID,
    val email: String,
    val passwordHash: String,
)

class AuthRepository(private val dataSource: DataSource) {

    suspend fun createUser(email: String, passwordHash: String): UserRecord? = dataSource.transaction { conn ->
        conn.prepareStatement(
            """
            INSERT INTO users (email, password_hash) VALUES (?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id, email, password_hash
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, email)
            ps.setString(2, passwordHash)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    suspend fun findByEmail(email: String): UserRecord? = dataSource.transaction { conn ->
        conn.prepareStatement("SELECT id, email, password_hash FROM users WHERE lower(email) = lower(?)").use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    suspend fun saveRefreshToken(userId: UUID, tokenHash: String, ttlSeconds: Long) {
        dataSource.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, now() + (? * interval '1 second'))
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, tokenHash)
                ps.setLong(3, ttlSeconds)
                ps.executeUpdate()
            }
        }
    }

    suspend fun findActiveRefreshTokenOwner(tokenHash: String): UserRecord? = dataSource.transaction { conn ->
        conn.prepareStatement(
            """
            SELECT u.id, u.email, u.password_hash
            FROM refresh_tokens t
            JOIN users u ON u.id = t.user_id
            WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > now()
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, tokenHash)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    suspend fun revokeRefreshToken(tokenHash: String) {
        dataSource.transaction { conn ->
            conn.prepareStatement(
                "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL",
            ).use { ps ->
                ps.setString(1, tokenHash)
                ps.executeUpdate()
            }
        }
    }

    private fun ResultSet.toUser() = UserRecord(
        id = getObject("id", UUID::class.java),
        email = getString("email"),
        passwordHash = getString("password_hash"),
    )
}
