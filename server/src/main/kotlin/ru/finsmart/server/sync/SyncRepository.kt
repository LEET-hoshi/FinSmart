package ru.finsmart.server.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.Categories
import ru.finsmart.server.common.transaction
import ru.finsmart.server.goals.goalIdTaken
import ru.finsmart.server.goals.insertGoal
import ru.finsmart.server.goals.lockGoal
import ru.finsmart.server.goals.updateGoal
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class SyncRepository(private val dataSource: DataSource) {

    suspend fun findRecorded(userId: UUID, clientChangeId: UUID): RecordedChange? =
        dataSource.transaction { conn ->
            conn.prepareStatement(
                "SELECT entity_type, entity_id, version FROM sync_changes WHERE user_id = ? AND client_change_id = ?",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setObject(2, clientChangeId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        RecordedChange(
                            entityType = rs.getString("entity_type"),
                            entityId = rs.getObject("entity_id", UUID::class.java),
                            version = rs.getLong("version"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    suspend fun applyUpsert(userId: UUID, change: SyncChange): Long? =
        try {
            dataSource.transaction { conn ->
                when (change.entityType) {
                    ENTITY_GOAL -> conn.upsertGoal(userId, change)
                    ENTITY_TRANSACTION_CATEGORY -> conn.setManualCategory(userId, change)
                }
                conn.record(userId, change) ?: throw AlreadyRecorded()
            }
        } catch (e: AlreadyRecorded) {
            null
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) throw ApiException.notFound()
            throw e
        }

    suspend fun cursor(userId: UUID): Long? = dataSource.transaction { conn ->
        conn.prepareStatement("SELECT max(id) FROM sync_changes WHERE user_id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1).takeUnless { rs.wasNull() }
            }
        }
    }

    private fun Connection.upsertGoal(userId: UUID, change: SyncChange) {
        val existing = lockGoal(userId, change.entityId)
        when {
            existing != null -> updateGoal(userId, existing, change.payload)
            goalIdTaken(change.entityId) -> throw ApiException.notFound()
            else -> insertGoal(userId, change.payload, change.entityId)
        }
    }

    private fun Connection.setManualCategory(userId: UUID, change: SyncChange) {
        val category = (change.payload["category"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        if (category == null || category !in Categories.CODES) {
            throw ApiException.validation(mapOf("category" to "Неизвестная категория"))
        }
        val updated = prepareStatement(
            """
            UPDATE transactions
            SET category_code = ?, category_manual = TRUE, updated_at = now()
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, category)
            ps.setObject(2, change.entityId)
            ps.setObject(3, userId)
            ps.executeUpdate()
        }
        if (updated == 0) throw ApiException.notFound()
    }

    private fun Connection.record(userId: UUID, change: SyncChange): Long? =
        prepareStatement(
            """
            INSERT INTO sync_changes (user_id, client_change_id, entity_type, entity_id, operation, version)
            VALUES (?, ?, ?, ?, ?, (
                SELECT COALESCE(max(version), 0) + 1 FROM sync_changes
                WHERE user_id = ? AND entity_type = ? AND entity_id = ?
            ))
            ON CONFLICT (user_id, client_change_id) DO NOTHING
            RETURNING version
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, change.clientChangeId)
            ps.setString(3, change.entityType)
            ps.setObject(4, change.entityId)
            ps.setString(5, change.operation)
            ps.setObject(6, userId)
            ps.setString(7, change.entityType)
            ps.setObject(8, change.entityId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private class AlreadyRecorded : RuntimeException()

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
