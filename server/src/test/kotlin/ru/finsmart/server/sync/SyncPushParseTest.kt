package ru.finsmart.server.sync

import kotlinx.serialization.json.JsonObject
import ru.finsmart.server.common.ApiException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncPushParseTest {
    private fun change(
        clientChangeId: String? = UUID.randomUUID().toString(),
        entityType: String? = ENTITY_GOAL,
        entityId: String? = UUID.randomUUID().toString(),
        operation: String? = OPERATION_UPSERT,
        payload: JsonObject? = JsonObject(emptyMap()),
    ) = SyncChangeRequest(clientChangeId, entityType, entityId, operation, payload)

    @Test
    fun valid_batch_is_parsed() {
        val parsed = SyncService.parse(SyncPushRequest(listOf(change(), change(entityType = ENTITY_TRANSACTION_CATEGORY))))

        assertEquals(2, parsed.size)
        assertEquals(ENTITY_TRANSACTION_CATEGORY, parsed[1].entityType)
    }

    @Test
    fun missing_or_empty_changes_are_rejected() {
        assertFailsWith<ApiException> { SyncService.parse(SyncPushRequest(null)) }
        assertFailsWith<ApiException> { SyncService.parse(SyncPushRequest(emptyList())) }
    }

    @Test
    fun batch_over_limit_is_rejected() {
        val e = assertFailsWith<ApiException> {
            SyncService.parse(SyncPushRequest(List(SyncService.MAX_CHANGES + 1) { change() }))
        }
        assertTrue(e.details!!.getValue("changes").contains("${SyncService.MAX_CHANGES}"))
    }

    @Test
    fun invalid_fields_are_reported_with_index() {
        val e = assertFailsWith<ApiException> {
            SyncService.parse(
                SyncPushRequest(
                    listOf(
                        change(),
                        change(clientChangeId = "abc", entityType = "offer", entityId = null, operation = "patch", payload = null),
                    ),
                ),
            )
        }

        assertEquals(
            setOf(
                "changes[1].clientChangeId",
                "changes[1].entityType",
                "changes[1].entityId",
                "changes[1].operation",
                "changes[1].payload",
            ),
            e.details!!.keys,
        )
    }

    @Test
    fun non_canonical_uuid_is_rejected() {
        val e = assertFailsWith<ApiException> {
            SyncService.parse(SyncPushRequest(listOf(change(entityId = "1-1-1-1-1"))))
        }
        assertEquals(setOf("changes[0].entityId"), e.details!!.keys)
    }

    @Test
    fun delete_does_not_require_payload() {
        val parsed = SyncService.parse(SyncPushRequest(listOf(change(operation = OPERATION_DELETE, payload = null))))

        assertEquals(OPERATION_DELETE, parsed.single().operation)
    }
}
