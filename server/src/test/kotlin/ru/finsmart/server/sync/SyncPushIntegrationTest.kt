package ru.finsmart.server.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ru.finsmart.server.TestDatabase
import ru.finsmart.server.common.AppConfig
import ru.finsmart.server.module
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPushIntegrationTest {
    private val config = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-secret-test-secret-test-secret!!"))
    private val dataSource = TestDatabase.dataSource

    private class User(val id: UUID, val token: String)

    private fun syncTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, dataSource) }
        block()
    }

    private suspend fun HttpClient.register(): User {
        val response = post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"sync-${UUID.randomUUID()}@test.local","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json().jsonObject
        return User(
            id = UUID.fromString(body.getValue("user").jsonObject.str("id")),
            token = body.str("accessToken"),
        )
    }

    private suspend fun HttpClient.push(user: User, vararg changes: String): HttpResponse =
        post("/sync/push") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"changes":[${changes.joinToString(",")}]}""")
        }

    private suspend fun HttpClient.goals(user: User): JsonArray =
        get("/goals") { header(HttpHeaders.Authorization, "Bearer ${user.token}") }.json().jsonArray

    private fun goalChange(clientChangeId: UUID, goalId: UUID, payload: String) =
        """{"clientChangeId":"$clientChangeId","entityType":"goal","entityId":"$goalId","operation":"upsert","payload":$payload}"""

    private fun categoryChange(clientChangeId: UUID, transactionId: UUID, category: String) =
        """{"clientChangeId":"$clientChangeId","entityType":"transaction_category","entityId":"$transactionId","operation":"upsert","payload":{"category":"$category"}}"""

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun HttpResponse.results(): List<JsonObject> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return json().jsonObject.getValue("results").jsonArray.map { it.jsonObject }
    }

    private fun insertTransaction(userId: UUID): UUID = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO transactions (user_id, external_id, operation_date, amount, description, mcc, category_code) " +
                "VALUES (?, ?, CURRENT_DATE, -350.00, 'Магазин', '5411', 'groceries') RETURNING id",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, "sync-${UUID.randomUUID()}")
            ps.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
        }
    }

    private fun transactionCategory(id: UUID): Pair<String, Boolean> = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT category_code, category_manual FROM transactions WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) to rs.getBoolean(2) }
        }
    }

    private fun syncChangesCount(userId: UUID): Int = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT count(*) FROM sync_changes WHERE user_id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun repeated_push_does_not_create_duplicates() = syncTest {
        val user = client.register()
        val change = goalChange(UUID.randomUUID(), UUID.randomUUID(), """{"title":"Отпуск","targetAmount":"50000"}""")

        val first = client.push(user, change).results().single()
        val second = client.push(user, change).results().single()

        assertEquals(STATUS_APPLIED, first.str("status"))
        assertEquals(STATUS_DUPLICATE, second.str("status"))
        assertEquals(first.str("version"), second.str("version"))
        assertEquals(1, client.goals(user).size)
        assertEquals(1, syncChangesCount(user.id))
    }

    @Test
    fun same_change_twice_in_one_batch_is_applied_once() = syncTest {
        val user = client.register()
        val change = goalChange(UUID.randomUUID(), UUID.randomUUID(), """{"title":"Отпуск","targetAmount":"50000"}""")

        val results = client.push(user, change, change).results()

        assertEquals(listOf(STATUS_APPLIED, STATUS_DUPLICATE), results.map { it.str("status") })
        assertEquals(1, client.goals(user).size)
    }

    @Test
    fun goal_is_created_then_updated_with_growing_version() = syncTest {
        val user = client.register()
        val goalId = UUID.randomUUID()

        val created = client.push(user, goalChange(UUID.randomUUID(), goalId, """{"title":"Отпуск","targetAmount":"50000"}""")).results().single()
        val updated = client.push(user, goalChange(UUID.randomUUID(), goalId, """{"currentAmount":"12000"}""")).results().single()

        assertEquals(1L, created.getValue("version").jsonPrimitive.long)
        assertEquals(2L, updated.getValue("version").jsonPrimitive.long)
        val goal = client.goals(user).single().jsonObject
        assertEquals(goalId.toString(), goal.str("id"))
        assertEquals("12000.00", goal.str("currentAmount"))
    }

    @Test
    fun foreign_goal_is_rejected_and_left_unchanged() = syncTest {
        val owner = client.register()
        val stranger = client.register()
        val goalId = UUID.randomUUID()
        client.push(owner, goalChange(UUID.randomUUID(), goalId, """{"title":"Отпуск","targetAmount":"50000"}""")).results()

        val result = client.push(stranger, goalChange(UUID.randomUUID(), goalId, """{"title":"Чужое","targetAmount":"1"}""")).results().single()

        assertEquals(STATUS_REJECTED, result.str("status"))
        assertEquals("not_found", result.getValue("error").jsonObject.str("code"))
        assertEquals("Отпуск", client.goals(owner).single().jsonObject.str("title"))
        assertTrue(client.goals(stranger).isEmpty())
        assertEquals(0, syncChangesCount(stranger.id))
    }

    @Test
    fun manual_category_is_applied_and_foreign_transaction_is_rejected() = syncTest {
        val owner = client.register()
        val stranger = client.register()
        val transactionId = insertTransaction(owner.id)

        val applied = client.push(owner, categoryChange(UUID.randomUUID(), transactionId, "cafes")).results().single()
        val rejected = client.push(stranger, categoryChange(UUID.randomUUID(), transactionId, "health")).results().single()

        assertEquals(STATUS_APPLIED, applied.str("status"))
        assertEquals(STATUS_REJECTED, rejected.str("status"))
        assertEquals("not_found", rejected.getValue("error").jsonObject.str("code"))
        assertEquals("cafes" to true, transactionCategory(transactionId))
    }

    @Test
    fun invalid_change_is_rejected_without_blocking_the_rest() = syncTest {
        val user = client.register()
        val transactionId = insertTransaction(user.id)

        val results = client.push(
            user,
            goalChange(UUID.randomUUID(), UUID.randomUUID(), """{"title":"Лимит","type":"spending_limit","targetAmount":"5000"}"""),
            categoryChange(UUID.randomUUID(), transactionId, "unknown"),
            """{"clientChangeId":"${UUID.randomUUID()}","entityType":"goal","entityId":"${UUID.randomUUID()}","operation":"delete"}""",
            categoryChange(UUID.randomUUID(), transactionId, "transport"),
        ).results()

        assertEquals(
            listOf(STATUS_REJECTED, STATUS_REJECTED, STATUS_REJECTED, STATUS_APPLIED),
            results.map { it.str("status") },
        )
        assertEquals("validation_error", results[0].getValue("error").jsonObject.str("code"))
        assertEquals("validation_error", results[1].getValue("error").jsonObject.str("code"))
        assertEquals("unsupported_operation", results[2].getValue("error").jsonObject.str("code"))
        assertTrue(client.goals(user).isEmpty())
        assertEquals("transport" to true, transactionCategory(transactionId))
    }

    @Test
    fun cursor_is_last_recorded_change_of_user() = syncTest {
        val user = client.register()
        val empty = client.push(
            user,
            """{"clientChangeId":"${UUID.randomUUID()}","entityType":"goal","entityId":"${UUID.randomUUID()}","operation":"delete"}""",
        )
        assertEquals(HttpStatusCode.OK, empty.status)
        assertNull(empty.json().jsonObject.getValue("cursor").jsonPrimitive.content.toLongOrNull())

        val response = client.push(
            user,
            goalChange(UUID.randomUUID(), UUID.randomUUID(), """{"title":"А","targetAmount":"1"}"""),
            goalChange(UUID.randomUUID(), UUID.randomUUID(), """{"title":"Б","targetAmount":"1"}"""),
        )
        val cursor = response.json().jsonObject.getValue("cursor").jsonPrimitive.long
        val maxId = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT max(id) FROM sync_changes WHERE user_id = ?").use { ps ->
                ps.setObject(1, user.id)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertEquals(maxId, cursor)
    }

    @Test
    fun malformed_batch_returns_400_and_requires_auth() = syncTest {
        val user = client.register()

        val invalid = client.push(user, """{"clientChangeId":"x","entityType":"goal","entityId":"y","operation":"upsert","payload":{}}""")
        val anonymous = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            setBody("""{"changes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("validation_error", invalid.json().jsonObject.str("code"))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
    }
}
