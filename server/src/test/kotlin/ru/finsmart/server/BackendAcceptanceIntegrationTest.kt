package ru.finsmart.server

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.finsmart.server.common.AppConfig
import ru.finsmart.server.goals.BUSINESS_ZONE
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendAcceptanceIntegrationTest {
    private val config = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-secret-test-secret-test-secret!!"))
    private val dataSource = TestDatabase.dataSource

    private class User(val token: String)

    private fun apiTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, dataSource) }
        block()
    }

    private suspend fun HttpClient.register(): User {
        val response = post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"it-${UUID.randomUUID()}@test.local","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return User(response.json().jsonObject.str("accessToken"))
    }

    private suspend fun HttpClient.importCsv(user: User, fixture: String): JsonObject {
        val bytes = File("../fixtures/csv/$fixture").readBytes()
        val response = post("/transactions/import") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "text/csv")
                                append(HttpHeaders.ContentDisposition, "filename=\"$fixture\"")
                            },
                        )
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json().jsonObject
    }

    private suspend fun HttpClient.authGet(user: User, path: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer ${user.token}") }

    private suspend fun HttpClient.authJson(user: User, path: String): JsonObject {
        val response = authGet(user, path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json().jsonObject
    }

    private suspend fun HttpClient.authPatch(user: User, path: String, body: String): HttpResponse =
        patch(path) {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.transactions(user: User, query: String = "limit=200"): JsonObject =
        authJson(user, "/transactions?$query")

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }

    private fun insertOffer(sourceId: String, category: String, validTo: LocalDate?): UUID =
        dataSource.connection.use { conn ->
            val id = conn.prepareStatement(
                "INSERT INTO offers (source_id, title, regular_price, sale_price, valid_to, url, is_test, checked_at) " +
                    "VALUES (?, ?, 200.00, 150.00, ?, 'https://magnit.ru/', TRUE, now()) RETURNING id",
            ).use { ps ->
                ps.setString(1, sourceId)
                ps.setString(2, sourceId)
                ps.setObject(3, validTo)
                ps.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
            }
            conn.prepareStatement("INSERT INTO offer_categories (offer_id, category_code) VALUES (?, ?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, category)
                ps.executeUpdate()
            }
            id
        }

    @Test
    fun user_cannot_read_or_change_foreign_data() = apiTest {
        val owner = client.register()
        val stranger = client.register()
        client.importCsv(owner, "valid.csv")
        val ownerTransaction = client.transactions(owner).items().first()
        val goal = client.post("/goals") {
            header(HttpHeaders.Authorization, "Bearer ${owner.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Отпуск","targetAmount":"50000"}""")
        }.json().jsonObject

        assertEquals(0, client.transactions(stranger).int("total"))
        assertTrue(client.authGet(stranger, "/goals").json().jsonArray.isEmpty())
        assertEquals("0.00", client.authJson(stranger, "/analytics/summary?month=2026-10").str("totalExpense"))

        val foreignCategory = client.authPatch(
            stranger,
            "/transactions/${ownerTransaction.str("id")}/category",
            """{"category":"health"}""",
        )
        val foreignGoal = client.authPatch(stranger, "/goals/${goal.str("id")}", """{"title":"Чужое"}""")

        assertEquals(HttpStatusCode.NotFound, foreignCategory.status)
        assertEquals(HttpStatusCode.NotFound, foreignGoal.status)
        val ownerAfter = client.transactions(owner).items().first { it.str("id") == ownerTransaction.str("id") }
        assertEquals(ownerTransaction.str("category"), ownerAfter.str("category"))
        assertEquals("Отпуск", client.authGet(owner, "/goals").json().jsonArray.single().jsonObject.str("title"))
    }

    @Test
    fun repeated_import_does_not_create_duplicates() = apiTest {
        val user = client.register()

        val first = client.importCsv(user, "valid.csv")
        val second = client.importCsv(user, "valid.csv")
        val partial = client.importCsv(user, "duplicates.csv")

        assertEquals(37 to 0, first.int("imported") to first.int("duplicates"))
        assertEquals(0 to 37, second.int("imported") to second.int("duplicates"))
        assertEquals(3 to 6, partial.int("imported") to partial.int("duplicates"))
        assertEquals(40, client.transactions(user).int("total"))
    }

    @Test
    fun manual_category_survives_reimport() = apiTest {
        val user = client.register()
        client.importCsv(user, "valid.csv")
        val transaction = client.transactions(user).items().first { it.str("category") != "cafes" }

        val changed = client.authPatch(user, "/transactions/${transaction.str("id")}/category", """{"category":"cafes"}""")
        client.importCsv(user, "valid.csv")

        assertEquals(HttpStatusCode.OK, changed.status)
        val after = client.transactions(user).items().first { it.str("id") == transaction.str("id") }
        assertEquals("cafes", after.str("category"))
        assertEquals("true", after.str("categoryManual"))
    }

    @Test
    fun unknown_mcc_falls_back_to_other() = apiTest {
        val user = client.register()

        val result = client.importCsv(user, "unknown_mcc.csv")

        assertEquals(4 to 0, result.int("imported") to result.int("failed"))
        val byDescription = client.transactions(user).items().associate { it.str("description") to it.str("category") }
        assertEquals(
            mapOf(
                "NEIZVESTNYI MAGAZIN" to "other",
                "PEREVOD BEZ MCC" to "other",
                "KNIZHNYI CHITAI-GOROD" to "other",
                "MAGNIT MM EKATERINBURG" to "groceries",
            ),
            byDescription,
        )
    }

    @Test
    fun analytics_matches_fixture() = apiTest {
        val user = client.register()
        client.importCsv(user, "valid.csv")

        val summary = client.authJson(user, "/analytics/summary?month=2026-10")

        assertEquals("30078.15", summary.str("totalExpense"))
        assertEquals("13338.90", summary.str("previousTotalExpense"))
        assertEquals("125.5", summary.str("changePercent"))
        assertEquals("home", summary.str("topCategory"))
        assertEquals(21, summary.int("transactionsCount"))
        val home = summary.getValue("categories").jsonArray.map { it.jsonObject }.first { it.str("category") == "home" }
        assertEquals("8690.00", home.str("amount"))
        assertEquals("28.9", home.str("sharePercent"))
    }

    @Test
    fun recommendation_route_returns_offers_action_for_the_fastest_growing_category() = apiTest {
        val user = client.register()
        client.importCsv(user, "valid.csv")

        val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")

        assertEquals("open_offers", recommendation.str("actionType"))
        assertTrue(recommendation.str("category") in setOf("groceries", "cafes", "transport", "health", "home", "entertainment", "other"))
        assertEquals("rules", recommendation.str("source"))
    }

    @Test
    fun recommendation_route_is_neutral_without_a_previous_period() = apiTest {
        val user = client.register()

        val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")

        assertEquals("none", recommendation.str("actionType"))
        assertEquals("null", recommendation.getValue("category").toString())
    }

    @Test
    fun recommendation_route_passes_active_goal_progress_to_the_provider() = apiTest {
        val user = client.register()
        client.importCsv(user, "valid.csv")
        val goal = client.post("/goals") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Отпуск","targetAmount":"1000","currentAmount":"500"}""")
        }
        assertEquals(HttpStatusCode.Created, goal.status, goal.bodyAsText())

        val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")

        assertTrue(recommendation.str("message").contains("50.0%"))
    }

    @Test
    fun expired_offer_is_not_returned() = apiTest {
        val user = client.register()
        val today = LocalDate.now(BUSINESS_ZONE)
        val prefix = "it-${UUID.randomUUID()}"
        val expired = insertOffer("$prefix-expired", "entertainment", today.minusDays(1))
        val lastDay = insertOffer("$prefix-last-day", "entertainment", today)
        val open = insertOffer("$prefix-open", "entertainment", null)

        val listed = client.authJson(user, "/offers?category=entertainment").items()
            .map { it.str("sourceId") }
            .filter { it.startsWith(prefix) }

        assertEquals(setOf("$prefix-last-day", "$prefix-open"), listed.toSet())
        assertFalse("$prefix-expired" in listed)
        assertEquals(HttpStatusCode.NotFound, client.authGet(user, "/offers/$expired").status)
        assertEquals(HttpStatusCode.OK, client.authGet(user, "/offers/$lastDay").status)
        assertEquals(HttpStatusCode.OK, client.authGet(user, "/offers/$open").status)
    }
}
