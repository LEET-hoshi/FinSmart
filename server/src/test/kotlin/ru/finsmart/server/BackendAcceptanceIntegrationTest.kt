package ru.finsmart.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import ru.finsmart.server.common.LlmConfig
import ru.finsmart.server.goals.BUSINESS_ZONE
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendAcceptanceIntegrationTest {
    private val config = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-secret-test-secret-test-secret!!"))
    private val dataSource = TestDatabase.dataSource

    private class User(val token: String)

    private fun apiTest(
        appConfig: AppConfig = config,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { module(appConfig, dataSource) }
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
    fun mvp_route_from_registration_to_goal() = apiTest {
        val credentials = """{"email":"it-${UUID.randomUUID()}@test.local","password":"password123"}"""
        val registered = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(credentials)
        }
        assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())

        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(credentials)
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        val user = User(login.json().jsonObject.str("accessToken"))

        val imported = client.importCsv(user, "valid.csv")
        assertEquals(37 to 0, imported.int("imported") to imported.int("duplicates"))
        assertEquals(37, client.transactions(user).int("total"))

        val summary = client.authJson(user, "/analytics/summary?month=2026-10")
        assertEquals("30078.15", summary.str("totalExpense"))
        assertEquals("home", summary.str("topCategory"))

        val created = client.post("/goals") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Отпуск","targetAmount":"100000","currentAmount":"25000"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val goalId = created.json().jsonObject.str("id")

        val goals = client.authGet(user, "/goals")
        assertEquals(HttpStatusCode.OK, goals.status, goals.bodyAsText())
        val goal = goals.json().jsonArray.single().jsonObject
        assertEquals(goalId, goal.str("id"))
        assertEquals("25.0", goal.str("progressPercent"))

        val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")
        assertEquals("rules", recommendation.str("source"))
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
        assertEquals(6, client.importCsv(user, "recommendation-demo.csv").int("imported"))

        val summary = client.authJson(user, "/analytics/summary?month=2026-10")
        assertEquals("groceries", summary.str("topCategory"))
        assertRulesRecommendation(client.authJson(user, "/ai/recommendation?month=2026-10"))
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
    fun recommendation_uses_rules_when_llm_is_not_configured() = apiTest {
        val user = client.register()
        client.importCsv(user, "valid.csv")

        val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")

        assertEquals("rules", recommendation.str("source"))
        assertEquals("open_offers", recommendation.str("actionType"))
    }

    @Test
    fun recommendation_falls_back_to_rules_when_llm_endpoint_fails() = apiTest(
        config.copy(
            llm = LlmConfig(
                enabled = true,
                endpoint = "http://127.0.0.1:1/recommendation",
                timeout = Duration.ofMillis(100),
            ),
        ),
    ) {
        val user = client.register()
        client.importCsv(user, "valid.csv")

        val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")

        assertEquals("rules", recommendation.str("source"))
        assertEquals("open_offers", recommendation.str("actionType"))
    }

    @Test
    fun recommendation_falls_back_to_rules_when_llm_times_out() {
        val llmServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/recommendation") { exchange ->
                Thread.sleep(500)
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, 0)
                exchange.responseBody.close()
            }
            start()
        }
        val appConfig = config.copy(
            llm = LlmConfig(
                enabled = true,
                endpoint = "http://127.0.0.1:${llmServer.address.port}/recommendation",
                timeout = Duration.ofMillis(100),
            ),
        )

        try {
            apiTest(appConfig) {
                val user = client.register()
                client.importCsv(user, "valid.csv")

                val recommendation = client.authJson(user, "/ai/recommendation?month=2026-10")

                assertEquals("rules", recommendation.str("source"))
                assertEquals("open_offers", recommendation.str("actionType"))
            }
        } finally {
            llmServer.stop(0)
        }
    }

    @Test
    fun recommendation_returns_llm_result_and_sends_only_allowed_aggregates() {
        val payload = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        withLlmServer(handle = { exchange ->
            payload.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respondWithLlm(HttpStatusCode.OK, demoLlmResponse)
        }) {
            val user = client.register()
            assertEquals(6, client.importCsv(user, "recommendation-demo.csv").int("imported"))
            val goal = client.post("/goals") {
                header(HttpHeaders.Authorization, "Bearer ${user.token}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"DEMO_GOAL_TITLE","targetAmount":"1000","currentAmount":"500"}""")
            }
            assertEquals(HttpStatusCode.Created, goal.status, goal.bodyAsText())

            val result = client.authJson(user, "/ai/recommendation?month=2026-10")
            val expected = Json.parseToJsonElement(demoLlmResponse).jsonObject
            assertEquals(expected, JsonObject(result - "source"))
            assertEquals("llm", result.str("source"))
            assertEquals(null, authorization.get())
            val expectedPayload = Json.parseToJsonElement(
                """{"totalExpense":"1700.00","previousTotalExpense":"1150.00","changePercent":47.8,"goalProgressPercent":50.0,"categories":"groceries,home,cafes"}""",
            )
            assertEquals(expectedPayload, Json.parseToJsonElement(assertNotNull(payload.get())))
        }
    }

    @Test
    fun recommendation_falls_back_to_rules_on_llm_http_error() =
        withLlmServer(handle = { it.respondWithLlm(HttpStatusCode.ServiceUnavailable, demoLlmResponse) }) {
            val user = client.register()
            client.importCsv(user, "recommendation-demo.csv")
            assertRulesRecommendation(client.authJson(user, "/ai/recommendation?month=2026-10"))
        }

    @Test
    fun recommendation_falls_back_to_rules_on_invalid_llm_response() =
        withLlmServer(handle = { it.respondWithLlm(HttpStatusCode.OK, """{"title":""") }) {
            val user = client.register()
            client.importCsv(user, "recommendation-demo.csv")
            assertRulesRecommendation(client.authJson(user, "/ai/recommendation?month=2026-10"))
        }

    @Test
    fun recommendation_falls_back_to_rules_when_llm_delays_the_body() {
        val bodyStarted = CountDownLatch(1)
        val finishBody = CountDownLatch(1)
        withLlmServer(timeout = Duration.ofSeconds(1), handle = { exchange ->
            val body = demoLlmResponse.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { stream ->
                stream.write(body, 0, 1)
                stream.flush()
                bodyStarted.countDown()
                if (finishBody.await(5, TimeUnit.SECONDS)) return@use
                stream.write(body, 1, body.size - 1)
            }
        }) {
            val user = client.register()
            client.importCsv(user, "recommendation-demo.csv")
            try {
                assertRulesRecommendation(client.authJson(user, "/ai/recommendation?month=2026-10"))
                assertEquals(0L, bodyStarted.count)
            } finally {
                finishBody.countDown()
            }
        }
    }

    private fun assertRulesRecommendation(result: JsonObject) {
        assertEquals("rules", result.str("source"))
        assertEquals("Расходы выросли", result.str("title"))
        assertEquals("home", result.str("category"))
        assertEquals("open_offers", result.str("actionType"))
        assertEquals("Посмотреть скидки", result.str("actionLabel"))
        assertEquals(
            "Расходы в категории «Дом» выросли сильнее всего. Посмотрите скидки в этой категории.",
            result.str("message"),
        )
    }

    private val demoLlmResponse =
        """{"title":"Проверьте скидки","message":"Сравните предложения для категории «Дом» перед следующей покупкой.","category":"home","actionType":"open_offers","actionLabel":"Посмотреть скидки"}"""

    private fun HttpExchange.respondWithLlm(status: HttpStatusCode, response: String) {
        val body = response.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status.value, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun withLlmServer(
        timeout: Duration = Duration.ofSeconds(5),
        handle: (HttpExchange) -> Unit,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/recommendation") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val llm = LlmConfig(
                enabled = true,
                endpoint = "http://127.0.0.1:${server.address.port}/recommendation",
                timeout = timeout,
            )
            apiTest(config.copy(llm = llm), block)
        } finally {
            server.stop(0)
        }
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
