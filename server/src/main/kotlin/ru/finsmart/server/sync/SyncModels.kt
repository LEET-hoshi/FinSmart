package ru.finsmart.server.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import ru.finsmart.server.common.ApiError
import java.util.UUID

const val ENTITY_GOAL = "goal"
const val ENTITY_TRANSACTION_CATEGORY = "transaction_category"
const val OPERATION_UPSERT = "upsert"
const val OPERATION_DELETE = "delete"

const val STATUS_APPLIED = "applied"
const val STATUS_DUPLICATE = "duplicate"
const val STATUS_REJECTED = "rejected"

@Serializable
data class SyncPushRequest(
    val changes: List<SyncChangeRequest>? = null,
)

@Serializable
data class SyncChangeRequest(
    val clientChangeId: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val operation: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class SyncChangeResult(
    val clientChangeId: String,
    val status: String,
    val entityType: String,
    val entityId: String,
    val version: Long?,
    val error: ApiError?,
)

@Serializable
data class SyncPushResponse(
    val results: List<SyncChangeResult>,
    val cursor: Long?,
)

data class SyncChange(
    val clientChangeId: UUID,
    val entityType: String,
    val entityId: UUID,
    val operation: String,
    val payload: JsonObject,
)

data class RecordedChange(
    val entityType: String,
    val entityId: UUID,
    val version: Long,
)
