package ru.finsmart.server.sync

import kotlinx.serialization.json.JsonObject
import ru.finsmart.server.common.ApiError
import ru.finsmart.server.common.ApiException
import java.util.UUID

class SyncService(private val repository: SyncRepository) {

    suspend fun push(userId: UUID, request: SyncPushRequest): SyncPushResponse {
        val changes = parse(request)
        val results = changes.map { process(userId, it) }
        return SyncPushResponse(results = results, cursor = repository.cursor(userId))
    }

    private suspend fun process(userId: UUID, change: SyncChange): SyncChangeResult {
        repository.findRecorded(userId, change.clientChangeId)?.let { return duplicate(change, it) }
        if (change.operation == OPERATION_DELETE) {
            return rejected(change, ApiError("unsupported_operation", "Удаление через синхронизацию пока не поддерживается"))
        }
        return try {
            val version = repository.applyUpsert(userId, change)
            if (version != null) {
                result(change, STATUS_APPLIED, version)
            } else {
                val recorded = repository.findRecorded(userId, change.clientChangeId)
                    ?: throw IllegalStateException("sync change ${change.clientChangeId} lost")
                duplicate(change, recorded)
            }
        } catch (e: ApiException) {
            rejected(change, ApiError(e.code, e.message, e.details))
        }
    }

    private fun duplicate(change: SyncChange, recorded: RecordedChange) = SyncChangeResult(
        clientChangeId = change.clientChangeId.toString(),
        status = STATUS_DUPLICATE,
        entityType = recorded.entityType,
        entityId = recorded.entityId.toString(),
        version = recorded.version,
        error = null,
    )

    private fun result(change: SyncChange, status: String, version: Long) = SyncChangeResult(
        clientChangeId = change.clientChangeId.toString(),
        status = status,
        entityType = change.entityType,
        entityId = change.entityId.toString(),
        version = version,
        error = null,
    )

    private fun rejected(change: SyncChange, error: ApiError) = SyncChangeResult(
        clientChangeId = change.clientChangeId.toString(),
        status = STATUS_REJECTED,
        entityType = change.entityType,
        entityId = change.entityId.toString(),
        version = null,
        error = error,
    )

    companion object {
        const val MAX_CHANGES = 100

        private val ENTITY_TYPES = setOf(ENTITY_GOAL, ENTITY_TRANSACTION_CATEGORY)
        private val OPERATIONS = setOf(OPERATION_UPSERT, OPERATION_DELETE)

        fun parse(request: SyncPushRequest): List<SyncChange> {
            val changes = request.changes
                ?: throw ApiException.validation(mapOf("changes" to "Обязательное поле"))
            if (changes.isEmpty()) throw ApiException.validation(mapOf("changes" to "Передайте хотя бы одно изменение"))
            if (changes.size > MAX_CHANGES) {
                throw ApiException.validation(mapOf("changes" to "Не больше $MAX_CHANGES изменений за запрос"))
            }

            val errors = linkedMapOf<String, String>()
            val parsed = changes.mapIndexedNotNull { i, raw ->
                val prefix = "changes[$i]"
                val clientChangeId = raw.clientChangeId.toUuidOrNull()
                if (clientChangeId == null) errors["$prefix.clientChangeId"] = "Ожидается UUID"
                val entityType = raw.entityType?.takeIf { it in ENTITY_TYPES }
                if (entityType == null) errors["$prefix.entityType"] = "goal или transaction_category"
                val entityId = raw.entityId.toUuidOrNull()
                if (entityId == null) errors["$prefix.entityId"] = "Ожидается UUID"
                val operation = raw.operation?.takeIf { it in OPERATIONS }
                if (operation == null) errors["$prefix.operation"] = "upsert или delete"
                val payload = raw.payload ?: if (operation == OPERATION_DELETE) JsonObject(emptyMap()) else null
                if (payload == null) errors["$prefix.payload"] = "Обязательное поле"

                if (clientChangeId == null || entityType == null || entityId == null || operation == null || payload == null) {
                    null
                } else {
                    SyncChange(clientChangeId, entityType, entityId, operation, payload)
                }
            }
            if (errors.isNotEmpty()) throw ApiException.validation(errors)
            return parsed
        }

        private fun String?.toUuidOrNull(): UUID? =
            this?.let { text -> runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString() == text.lowercase() } }
    }
}
