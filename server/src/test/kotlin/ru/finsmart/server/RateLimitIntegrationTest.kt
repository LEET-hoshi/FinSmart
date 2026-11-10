package ru.finsmart.server

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.finsmart.server.common.AppConfig
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RateLimitIntegrationTest {
    private val config = AppConfig.fromEnv(
        mapOf(
            "JWT_SECRET" to "test-secret-test-secret-test-secret!!",
            "RATE_LIMIT_AUTH_PER_MINUTE" to "3",
            "RATE_LIMIT_IMPORT_PER_MINUTE" to "2",
        ),
    )
    private val dataSource = TestDatabase.dataSource

    private fun limitTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, dataSource) }
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun HttpClient.authPost(path: String, body: String): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.register(): JsonObject {
        val response = authPost("/auth/register", """{"email":"rl-${UUID.randomUUID()}@test.local","password":"password123"}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json()
    }

    private suspend fun HttpClient.importCsv(token: String): HttpResponse =
        post("/transactions/import") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            File("../fixtures/csv/unknown_mcc.csv").readBytes(),
                            Headers.build {
                                append(HttpHeaders.ContentType, "text/csv")
                                append(HttpHeaders.ContentDisposition, "filename=\"unknown_mcc.csv\"")
                            },
                        )
                    },
                ),
            )
        }

    private suspend fun assertTooManyRequests(response: HttpResponse) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("too_many_requests", response.json().str("code"))
        assertNotNull(response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun login_is_limited_but_refresh_is_not() = limitTest {
        val session = client.register()
        val wrongLogin = """{"email":"${session.getValue("user").jsonObject.str("email")}","password":"wrongpass1"}"""

        assertEquals(HttpStatusCode.Unauthorized, client.authPost("/auth/login", wrongLogin).status)
        assertEquals(HttpStatusCode.Unauthorized, client.authPost("/auth/login", wrongLogin).status)
        assertTooManyRequests(client.authPost("/auth/login", wrongLogin))
        assertTooManyRequests(client.authPost("/auth/register", """{"email":"rl-${UUID.randomUUID()}@test.local","password":"password123"}"""))

        val refresh = client.authPost("/auth/refresh", """{"refreshToken":"${session.str("refreshToken")}"}""")
        assertEquals(HttpStatusCode.OK, refresh.status)
    }

    @Test
    fun import_is_limited_per_user() = limitTest {
        val first = client.register().str("accessToken")
        val second = client.register().str("accessToken")

        assertEquals(HttpStatusCode.OK, client.importCsv(first).status)
        assertEquals(HttpStatusCode.OK, client.importCsv(first).status)
        assertTooManyRequests(client.importCsv(first))
        assertEquals(HttpStatusCode.OK, client.importCsv(second).status)
    }
}
